package com.flashforge.farm.api;

import android.app.Service;
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
    private Handler handler;
    private Runnable pollRunnable;

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
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }

        handler = new Handler(Looper.getMainLooper());
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                checkQueueAndDispatch();
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        handler.post(pollRunnable);
    }

    private void checkQueueAndDispatch() {
        List<PrintQueueManager.QueueItem> queue = PrintQueueManager.getQueue();
        if (queue.isEmpty()) return;

        PrintQueueManager.QueueItem nextJob = null;
        for (PrintQueueManager.QueueItem item : queue) {
            if ("Pending".equals(item.status)) {
                nextJob = item;
                break;
            }
        }

        if (nextJob == null) return;

        List<PrinterFleetManager.Printer> printers = PrinterFleetManager.getPrinters();
        for (PrinterFleetManager.Printer printer : printers) {
            if (printer.nozzleSize.equals(nextJob.requiredNozzleSize) &&
                printer.loadedFilamentType.equalsIgnoreCase(nextJob.requiredFilamentType) &&
                printer.loadedFilamentColor.equalsIgnoreCase(nextJob.requiredFilamentColor)) {

                checkPrinterIdleAndSend(printer, nextJob);
                // Try only one matched printer per polling cycle to avoid race conditions
                break;
            }
        }
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
        return START_STICKY;
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
