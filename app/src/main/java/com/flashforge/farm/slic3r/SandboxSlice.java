package com.flashforge.farm.slic3r;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * App-side client for the isolated slice sandbox (issue #47).
 *
 * Slices by exporting the live model to 3MF, then running read + slice
 * inside SliceSandboxService and reconstructing the preview result from
 * the G-code file the sandbox produced.
 *
 * Fallback policy (security-critical): infra failures (bind/send setup
 * problems, ERROR_INFRA from the service) fall back to the in-process
 * slice. Anything at or after job acceptance — transact failure (binder
 * died mid-job), timeout-shaped hangs, ERROR_CONTENT — is treated as a
 * hostile/broken-input signal and thrown WITHOUT in-process fallback:
 * falling back on a sandbox crash would re-expose the app to the exact
 * exploit the sandbox contains (crash to force in-process parsing).
 */
public final class SandboxSlice {
    private static final String TAG = "SandboxSlice";
    private static final long BIND_TIMEOUT_MS = 10000;

    /** Setup-side failure: safe to slice in-process instead. */
    public static final class InfraFailure extends Exception {
        InfraFailure(String msg) {
            super(msg);
        }

        InfraFailure(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    private SandboxSlice() {
    }

    public static GCodeProcessorResult sliceOrLocal(Context ctx, Model model,
            String configPath, File gcodeFile, SliceListener listener,
            int numFilaments, int[] colors,
            int calibMode, double calibStart, double calibEnd, double calibStep)
            throws Slic3rRuntimeError {
        try {
            return sliceSandboxed(ctx, model, configPath, gcodeFile, listener,
                    numFilaments, colors, calibMode, calibStart, calibEnd, calibStep);
        } catch (InfraFailure e) {
            Log.w(TAG, "sandbox unavailable, slicing in-process", e);
            return new GCodeProcessorResult(Native.model_slice(model.pointer,
                    configPath, gcodeFile.getAbsolutePath(), listener,
                    numFilaments, colors, calibMode, calibStart, calibEnd, calibStep));
        }
    }

    private static GCodeProcessorResult sliceSandboxed(Context ctx, Model model,
            String configPath, File gcodeFile, final SliceListener listener,
            int numFilaments, int[] colors,
            int calibMode, double calibStart, double calibEnd, double calibStep)
            throws Slic3rRuntimeError, InfraFailure {
        if (ctx == null) {
            throw new InfraFailure("no context (unit test?)");
        }
        Context app = ctx.getApplicationContext();
        File cache = app.getCacheDir();
        File tmpModel;
        try {
            tmpModel = File.createTempFile("sandbox_slice_", ".3mf", cache);
        } catch (Exception e) {
            throw new InfraFailure("no temp model file", e);
        }
        try {
            model.export3mf(configPath, tmpModel.getAbsolutePath());
        } catch (Slic3rRuntimeError e) {
            throw new InfraFailure("model export failed", e);
        }
        try {
            if (!gcodeFile.exists()) {
                try {
                    gcodeFile.createNewFile();
                } catch (Exception e) {
                    throw new InfraFailure("no gcode file", e);
                }
            }
        } catch (InfraFailure e) {
            throw e;
        } catch (Exception e) {
            throw new InfraFailure("gcode file probe failed", e);
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ISliceSandbox> api = new AtomicReference<>();
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                api.set(ISliceSandbox.Stub.asInterface(service));
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                api.set(null);
            }
        };
        boolean bound = false;
        ParcelFileDescriptor modelFd = null;
        ParcelFileDescriptor configFd = null;
        ParcelFileDescriptor outFd = null;
        try {
            try {
                bound = app.bindService(
                        new Intent(app, SliceSandboxService.class),
                        conn, Context.BIND_AUTO_CREATE);
            } catch (Exception e) {
                throw new InfraFailure("bindService threw", e);
            }
            if (!bound) {
                throw new InfraFailure("bindService refused");
            }
            try {
                if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw new InfraFailure("service connect timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InfraFailure("service connect interrupted", e);
            }
            ISliceSandbox sandbox = api.get();
            if (sandbox == null) {
                throw new InfraFailure("service never connected");
            }
            try {
                modelFd = ParcelFileDescriptor.open(tmpModel,
                        ParcelFileDescriptor.MODE_READ_ONLY);
                configFd = ParcelFileDescriptor.open(new File(configPath),
                        ParcelFileDescriptor.MODE_READ_ONLY);
                outFd = ParcelFileDescriptor.open(gcodeFile,
                        ParcelFileDescriptor.MODE_READ_WRITE);
            } catch (Exception e) {
                throw new InfraFailure("fd open failed", e);
            }
            Bundle params = new Bundle();
            params.putInt(SandboxProto.KEY_NUM_FILAMENTS, numFilaments);
            params.putIntArray(SandboxProto.KEY_COLORS, colors);
            params.putInt(SandboxProto.KEY_CALIB_MODE, calibMode);
            params.putDouble(SandboxProto.KEY_CALIB_START, calibStart);
            params.putDouble(SandboxProto.KEY_CALIB_END, calibEnd);
            params.putDouble(SandboxProto.KEY_CALIB_STEP, calibStep);
            ISliceCallback callback = new ISliceCallback.Stub() {
                @Override
                public void onProgress(int progress, String text) {
                    // Runs on a Binder thread: forward immediately, never block.
                    listener.onProgress(progress, text);
                }
            };
            Bundle res;
            try {
                res = sandbox.slice(modelFd, configFd, outFd, params, callback);
            } catch (RemoteException e) {
                // Binder died at/after acceptance: content failure, no fallback.
                throw new Slic3rRuntimeError(
                        "slice sandbox died mid-job, refusing in-process fallback");
            }
            if (res == null) {
                throw new InfraFailure("null sandbox reply");
            }
            int status = res.getInt(SandboxProto.KEY_STATUS,
                    SandboxProto.STATUS_ERROR_INFRA);
            String detail = res.getString(SandboxProto.KEY_DETAIL, "");
            if (status == SandboxProto.STATUS_OK) {
                return new GCodeProcessorResult(gcodeFile);
            }
            if (status == SandboxProto.STATUS_ERROR_INFRA) {
                throw new InfraFailure("sandbox infra: " + detail);
            }
            throw new Slic3rRuntimeError("sandbox slice rejected: " + detail);
        } finally {
            closeQuietly(modelFd);
            closeQuietly(configFd);
            closeQuietly(outFd);
            if (bound) {
                try {
                    app.unbindService(conn);
                } catch (Exception ignored) {
                }
            }
            if (!tmpModel.delete()) {
                tmpModel.deleteOnExit();
            }
        }
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd == null) {
            return;
        }
        try {
            fd.close();
        } catch (Exception ignored) {
        }
    }
}
