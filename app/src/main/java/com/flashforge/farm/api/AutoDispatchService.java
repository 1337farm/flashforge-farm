package com.flashforge.farm.api;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.content.pm.ServiceInfo;

import androidx.core.app.NotificationCompat;
import android.util.Log;

import com.google.gson.JsonObject;

import com.flashforge.farm.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import com.flashforge.farm.utils.PrintQueueManager;
import com.flashforge.farm.utils.PrinterFleetManager;

public class AutoDispatchService extends Service {
    private static final String TAG = "AutoDispatchService";
    private static final long POLL_INTERVAL_MS = 15000; // Poll every 15 seconds
    private static final int MAX_IDLE_POLLS = 8; // Stop after ~2 min with no pending work
    private Handler handler;
    private Runnable pollRunnable;
    private int idlePolls = 0;

    /** Restart entry point: every start path must funnel here so a refused
     *  dataSync start (quota exhausted) degrades to stopped, never a crash. */
    public static void kick(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.startService(new Intent(ctx, AutoDispatchService.class));
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG, "Auto-dispatch start refused, staying stopped", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("farm_service", "Farm Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, "farm_service")
            .setContentTitle(getString(R.string.AppName))
            .setContentText("Monitoring print queue...")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } catch (ForegroundServiceStartNotAllowedException e) {
                // Android 15+ dataSync quota exhausted (6h/24h): a refused start
                // must degrade to stopped, never crash the host process.
                Log.w(TAG, "dataSync start refused (quota exhausted?), stopping", e);
                stopSelf();
                return;
            } catch (RuntimeException e) {
                // Sequential (not multi-) catch: subclass first is required and
                // always legal. The try holds exactly one framework call, so
                // this cannot mask app bugs — any other refusal still stops
                // instead of killing the process at launch.
                Log.w(TAG, "startForeground refused, stopping", e);
                stopSelf();
                return;
            }
        } else {
            startForeground(1, notification);
        }

        handler = new Handler(Looper.getMainLooper());
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!checkQueueAndDispatch()) {
                    idlePolls++;
                    if (idlePolls >= MAX_IDLE_POLLS) {
                        stopSelf();
                        return;
                    }
                } else {
                    idlePolls = 0;
                }
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        handler.post(pollRunnable);
    }

    // Android 15+ (API 35): the dataSync 6h/24h quota calls this before killing
    // the process. Stop within seconds so the timeout never becomes a crash.
    // (Never invoked below API 35; overriding is safe down to minSdk.)
    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "dataSync timeout, stopping (startId=" + startId + " type=" + fgsType + ")");
        stopSelf();
    }

    /** @return true if a pending job was found (service still has work). */
    private boolean checkQueueAndDispatch() {
        List<PrintQueueManager.QueueItem> queue = PrintQueueManager.getQueue();
        if (queue.isEmpty()) return false;

        PrintQueueManager.QueueItem nextJob = null;
        for (PrintQueueManager.QueueItem item : queue) {
            if ("Pending".equals(item.status)) {
                nextJob = item;
                break;
            }
        }

        if (nextJob == null) return false;

        List<PrinterFleetManager.Printer> printers = PrinterFleetManager.getPrinters();
        for (PrinterFleetManager.Printer printer : printers) {
            if (printer.nozzleSize.equals(nextJob.requiredNozzleSize) &&
                printer.loadedFilamentType.equalsIgnoreCase(nextJob.requiredFilamentType) &&
                printer.loadedFilamentColor.equalsIgnoreCase(nextJob.requiredFilamentColor)) {

                checkPrinterIdleAndSend(printer, nextJob);
                // Try only one matched printer per polling cycle to avoid race conditions
                return true;
            }
        }
        return true;
    }

    private void checkPrinterIdleAndSend(PrinterFleetManager.Printer printer, PrintQueueManager.QueueItem job) {
        FlashforgeAPIClient client = new FlashforgeAPIClient(printer.ipOrUrl);
        client.getPrinterStatus(new FlashforgeAPIClient.APIResultCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                // Determine if idle. The exact JSON structure for Flashforge API needs to be parsed here.
                // Assuming "status": "idle" or similar is returned.
                // For safety, we will try to start the print if we can read it.
                boolean isIdle = true;
                if (result.has("machineStatus") && !result.get("machineStatus").getAsString().equals("Ready")) {
                   isIdle = false;
                }

                if (isIdle) {
                    PrintQueueManager.updateJobStatus(job.id, "Printing");
                    uploadAndPrint(client, printer, job);
                }
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Failed to connect to printer: " + printer.ipOrUrl, e);
            }
        });
    }

    private void uploadAndPrint(FlashforgeAPIClient client, PrinterFleetManager.Printer printer, PrintQueueManager.QueueItem job) {
        new Thread(() -> {
            try {
                File gcodeFile = new File(job.gcodePath);
                String fileName = gcodeFile.getName();

                String baseUrl = printer.ipOrUrl.startsWith("http") ? printer.ipOrUrl : "http://" + printer.ipOrUrl;
                URL uploadUrl = new URL(baseUrl + (baseUrl.contains(":8898") ? "" : ":8898") + "/uploadGcode");
                HttpURLConnection conn = (HttpURLConnection) uploadUrl.openConnection();
                conn.setRequestMethod("POST");
                String boundary = "===" + System.currentTimeMillis() + "===";
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setDoOutput(true);

                OutputStream os = conn.getOutputStream();
                StringBuilder headerBuilder = new StringBuilder();
                headerBuilder.append("--").append(boundary).append((char)13).append((char)10);
                headerBuilder.append("Content-Disposition: form-data; name=").append((char)34).append("file").append((char)34).append("; filename=").append((char)34).append(fileName).append((char)34).append((char)13).append((char)10);
                headerBuilder.append("Content-Type: application/octet-stream").append((char)13).append((char)10).append((char)13).append((char)10);
                os.write(headerBuilder.toString().getBytes("UTF-8"));

                FileInputStream fis = new FileInputStream(gcodeFile);
                byte[] buffer = new byte[4096];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                fis.close();

                StringBuilder footerBuilder = new StringBuilder();
                footerBuilder.append((char)13).append((char)10).append("--").append(boundary).append("--").append((char)13).append((char)10);
                os.write(footerBuilder.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    URL printUrl = new URL(baseUrl + (baseUrl.contains(":8898") ? "" : ":8898") + "/printGcode");
                    HttpURLConnection printConn = (HttpURLConnection) printUrl.openConnection();
                    printConn.setRequestMethod("POST");
                    printConn.setRequestProperty("Content-Type", "application/json");
                    printConn.setDoOutput(true);

                    String payload = "{" + (char)34 + "file" + (char)34 + ":" + (char)34 + fileName + (char)34 + "}";
                    OutputStream printOs = printConn.getOutputStream();
                    printOs.write(payload.getBytes("UTF-8"));
                    printOs.flush();
                    printOs.close();

                    if (printConn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        PrintQueueManager.updateJobStatus(job.id, "Done");
                    } else {
                         PrintQueueManager.updateJobStatus(job.id, "Failed Start");
                    }
                } else {
                    PrintQueueManager.updateJobStatus(job.id, "Failed Upload");
                }
            } catch (Exception e) {
                Log.e(TAG, "Upload/Print failed", e);
                PrintQueueManager.updateJobStatus(job.id, "Failed");
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Lifecycle is explicit (app launch + job enqueue via kick()); never
        // let the system resurrect a dataSync FGS behind our back against the
        // 6h/24h quota.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
