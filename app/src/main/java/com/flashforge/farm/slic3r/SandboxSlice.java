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

import com.flashforge.farm.FarmApp;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * App-side client for the isolated slice sandbox (issue #47).
 *
 * Two operations, both sandbox-first with the same fallback policy:
 * <ul>
 * <li>slice: export the live model to 3MF, run read + slice inside the
 * sandbox, reconstruct the preview from the produced G-code file.</li>
 * <li>openModel: parse an untrusted model file inside the sandbox and
 * re-import the normalized 3MF it exports.</li>
 * </ul>
 *
 * Fallback policy (security-critical): infra failures (bind/send setup
 * problems, ERROR_INFRA from the service) fall back to the in-process
 * path. Anything at or after job acceptance — transact failure (binder
 * died mid-job), timeout-shaped hangs, ERROR_CONTENT — is treated as a
 * hostile/broken-input signal and thrown WITHOUT in-process fallback:
 * falling back on a sandbox crash would re-expose the app to the exact
 * exploit the sandbox contains (crash to force in-process parsing).
 */
public final class SandboxSlice {
    private static final String TAG = "SandboxSlice";
    private static final long BIND_TIMEOUT_MS = 10000;

    /** Setup-side failure: safe to run in-process instead. */
    public static final class InfraFailure extends Exception {
        InfraFailure(String msg) {
            super(msg);
        }

        InfraFailure(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** Bound service handle; close() unbinds. */
    private static final class Bound implements AutoCloseable {
        final Context app;
        final ISliceSandbox api;
        private final ServiceConnection conn;
        private final boolean bound;

        static Bound open(Context ctx) throws InfraFailure {
            if (ctx == null) {
                throw new InfraFailure("no context (unit test?)");
            }
            Context app = ctx.getApplicationContext();
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
            boolean bound;
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
                    try {
                        app.unbindService(conn);
                    } catch (Exception ignored) {
                    }
                    throw new InfraFailure("service connect timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                try {
                    app.unbindService(conn);
                } catch (Exception ignored) {
                }
                throw new InfraFailure("service connect interrupted", e);
            }
            ISliceSandbox sandbox = api.get();
            if (sandbox == null) {
                try {
                    app.unbindService(conn);
                } catch (Exception ignored) {
                }
                throw new InfraFailure("service never connected");
            }
            return new Bound(app, sandbox, conn, true);
        }

        private Bound(Context app, ISliceSandbox api, ServiceConnection conn,
                boolean bound) {
            this.app = app;
            this.api = api;
            this.conn = conn;
            this.bound = bound;
        }

        @Override
        public void close() {
            if (bound) {
                try {
                    app.unbindService(conn);
                } catch (Exception ignored) {
                }
            }
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
        Context app = ctx == null ? null : ctx.getApplicationContext();
        if (app == null) {
            throw new InfraFailure("no context (unit test?)");
        }
        File tmpModel;
        try {
            tmpModel = File.createTempFile("sandbox_slice_", ".3mf", app.getCacheDir());
        } catch (Exception e) {
            throw new InfraFailure("no temp model file", e);
        }
        try {
            model.export3mf(configPath, tmpModel.getAbsolutePath());
        } catch (Slic3rRuntimeError e) {
            throw new InfraFailure("model export failed", e);
        }
        try {
            if (!gcodeFile.exists() && !gcodeFile.createNewFile()) {
                throw new InfraFailure("no gcode file");
            }
        } catch (InfraFailure e) {
            throw e;
        } catch (Exception e) {
            throw new InfraFailure("gcode file probe failed", e);
        }

        ParcelFileDescriptor modelFd = null;
        ParcelFileDescriptor configFd = null;
        ParcelFileDescriptor outFd = null;
        try (Bound bound = Bound.open(ctx)) {
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
                res = bound.api.slice(modelFd, configFd, outFd, params, callback);
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
            if (!tmpModel.delete()) {
                tmpModel.deleteOnExit();
            }
        }
    }

    /**
     * Parse an untrusted model file inside the sandbox and return the
     * normalized 3MF it exports. Infra failures throw InfraFailure (caller
     * falls back to a direct open); content failures throw
     * Slic3rRuntimeError with no fallback.
     */
    public static File readInto(Context ctx, File srcFile, String baseName,
            int plateId, String configPath) throws Slic3rRuntimeError, InfraFailure {
        Context app = ctx == null ? null : ctx.getApplicationContext();
        if (app == null) {
            throw new InfraFailure("no context (unit test?)");
        }
        File out3mf;
        try {
            out3mf = File.createTempFile("sandbox_read_", ".3mf", app.getCacheDir());
        } catch (Exception e) {
            throw new InfraFailure("no temp model file", e);
        }
        ParcelFileDescriptor modelFd = null;
        ParcelFileDescriptor configFd = null;
        ParcelFileDescriptor outFd = null;
        boolean ok = false;
        try (Bound bound = Bound.open(ctx)) {
            try {
                modelFd = ParcelFileDescriptor.open(srcFile,
                        ParcelFileDescriptor.MODE_READ_ONLY);
                configFd = ParcelFileDescriptor.open(new File(configPath),
                        ParcelFileDescriptor.MODE_READ_ONLY);
                outFd = ParcelFileDescriptor.open(out3mf,
                        ParcelFileDescriptor.MODE_READ_WRITE);
            } catch (Exception e) {
                throw new InfraFailure("fd open failed", e);
            }
            Bundle params = new Bundle();
            params.putString(SandboxProto.KEY_BASENAME, baseName);
            params.putInt(SandboxProto.KEY_PLATE_ID, plateId);
            Bundle res;
            try {
                res = bound.api.read(modelFd, configFd, outFd, params);
            } catch (RemoteException e) {
                throw new Slic3rRuntimeError(
                        "slice sandbox died mid-read, refusing in-process fallback");
            }
            if (res == null) {
                throw new InfraFailure("null sandbox reply");
            }
            int status = res.getInt(SandboxProto.KEY_STATUS,
                    SandboxProto.STATUS_ERROR_INFRA);
            String detail = res.getString(SandboxProto.KEY_DETAIL, "");
            if (status == SandboxProto.STATUS_OK) {
                ok = true;
                return out3mf;
            }
            if (status == SandboxProto.STATUS_ERROR_INFRA) {
                throw new InfraFailure("sandbox infra: " + detail);
            }
            throw new Slic3rRuntimeError("sandbox read rejected: " + detail);
        } finally {
            closeQuietly(modelFd);
            closeQuietly(configFd);
            closeQuietly(outFd);
            if (!ok && !out3mf.delete()) {
                out3mf.deleteOnExit();
            }
        }
    }

    /**
     * Open a model file sandbox-first (issue #47 follow-up): parse untrusted
     * bytes in the isolated service, then construct from the normalized 3MF
     * it exports. Falls back to a direct open only on infra failures.
     */
    public static Model openModel(File f, int plateId) throws Slic3rRuntimeError {
        Context app = FarmApp.INSTANCE == null ? null
                : FarmApp.INSTANCE.getApplicationContext();
        String name = f.getName();
        String configPath;
        try {
            FarmApp.genCurrentConfig();
            configPath = FarmApp.getCurrentConfigFile().getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "no current config, opening in-process", e);
            return new Model(f, plateId);
        }
        try {
            File safe = readInto(app, f, name, plateId, configPath);
            try {
                return new Model(safe, plateId);
            } finally {
                if (!safe.delete()) {
                    safe.deleteOnExit();
                }
            }
        } catch (InfraFailure e) {
            Log.w(TAG, "sandbox unavailable, opening in-process", e);
            return new Model(f, plateId);
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
