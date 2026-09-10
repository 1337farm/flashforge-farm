package com.flashforge.farm.slic3r;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.flashforge.farm.modelrepo.safety.SafetyPolicy;

/**
 * Isolated slice sandbox (issue #47).
 *
 * Runs with android:isolatedProcess="true": no permissions, no filesystem
 * access outside FDs handed over Binder. The whole Slic3r/OCCT read + slice
 * pipeline for a job runs here, so a parser exploit lands in a
 * permission-less process. Files arrive as FDs and are opened natively via
 * /proc/self/fd (see SandboxProto.fdPath); all FDs here are regular files,
 * so fopen/fseek behave normally.
 *
 * Slice jobs are serialized on SLICE_LOCK: concurrent model_slice calls on
 * distinct models have unknown native thread-safety, and the app slices
 * serially today.
 */
public class SliceSandboxService extends Service {
    private static final String TAG = "SliceSandbox";
    private static final Object SLICE_LOCK = new Object();

    private static void closeFd(int fd) {
        if (fd < 0) {
            return;
        }
        try {
            ParcelFileDescriptor.adoptFd(fd).close();
        } catch (Exception ignored) {
        }
    }

    private static Bundle result(int status, String detail) {
        Bundle b = new Bundle();
        b.putInt(SandboxProto.KEY_STATUS, status);
        b.putString(SandboxProto.KEY_DETAIL, detail == null ? "" : detail);
        return b;
    }

    private final ISliceSandbox.Stub binder = new ISliceSandbox.Stub() {
        @Override
        public Bundle read(ParcelFileDescriptor modelFd, ParcelFileDescriptor configFd,
                ParcelFileDescriptor outFd, Bundle params) {
            if (modelFd == null || configFd == null || outFd == null || params == null) {
                return result(SandboxProto.STATUS_ERROR_INFRA, "missing fds or params");
            }
            int rawModel = -1;
            int rawConfig = -1;
            int rawOut = -1;
            try {
                long modelSize = modelFd.getStatSize();
                if (modelSize < 0) {
                    return result(SandboxProto.STATUS_ERROR_INFRA,
                            "model stat failed");
                }
                if (modelSize == 0 || modelSize > SafetyPolicy.MAX_PARSE_BYTES) {
                    return result(SandboxProto.STATUS_ERROR_CONTENT,
                            "model size out of bounds: " + modelSize);
                }
                rawModel = modelFd.detachFd();
                rawConfig = configFd.detachFd();
                rawOut = outFd.detachFd();
                String baseName = params.getString(SandboxProto.KEY_BASENAME, "sandbox.3mf");
                int plateId = params.getInt(SandboxProto.KEY_PLATE_ID, 0);
                synchronized (SLICE_LOCK) {
                    long modelPtr;
                    try {
                        modelPtr = Native.model_read_from_file(
                                SandboxProto.fdPath(rawModel), baseName, plateId);
                    } catch (LinkageError e) {
                        Log.e(TAG, "native libs unavailable in sandbox", e);
                        return result(SandboxProto.STATUS_ERROR_INFRA,
                                "native link failed: " + e.getMessage());
                    } catch (Slic3rRuntimeError e) {
                        return result(SandboxProto.STATUS_ERROR_CONTENT,
                                "sandbox read failed: " + e.getMessage());
                    }
                    try {
                        Native.model_export_3mf(modelPtr,
                                SandboxProto.fdPath(rawConfig),
                                SandboxProto.fdPath(rawOut));
                    } catch (Slic3rRuntimeError e) {
                        return result(SandboxProto.STATUS_ERROR_CONTENT,
                                "sandbox export failed: " + e.getMessage());
                    } finally {
                        try {
                            Native.model_release(modelPtr);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                return result(SandboxProto.STATUS_OK, "ok");
            } catch (Exception e) {
                Log.e(TAG, "sandbox read job failed", e);
                return result(SandboxProto.STATUS_ERROR_CONTENT,
                        "sandbox error: " + e.getMessage());
            } finally {
                closeFd(rawModel);
                closeFd(rawConfig);
                closeFd(rawOut);
                try {
                    modelFd.close();
                } catch (Exception ignored) {
                }
                try {
                    configFd.close();
                } catch (Exception ignored) {
                }
                try {
                    outFd.close();
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public Bundle slice(ParcelFileDescriptor modelFd, ParcelFileDescriptor configFd,
                ParcelFileDescriptor outFd, Bundle params, final ISliceCallback callback) {
            if (modelFd == null || configFd == null || outFd == null || params == null) {
                return result(SandboxProto.STATUS_ERROR_INFRA, "missing fds or params");
            }
            int rawModel = -1;
            int rawConfig = -1;
            int rawOut = -1;
            try {
                // getStatSize avoids opening a stream (a FileInputStream
                // finalizer could otherwise close the FD mid-job).
                long modelSize = modelFd.getStatSize();
                if (modelSize < 0) {
                    return result(SandboxProto.STATUS_ERROR_INFRA,
                            "model stat failed");
                }
                if (modelSize == 0 || modelSize > SafetyPolicy.MAX_PARSE_BYTES) {
                    return result(SandboxProto.STATUS_ERROR_CONTENT,
                            "model size out of bounds: " + modelSize);
                }
                rawModel = modelFd.detachFd();
                rawConfig = configFd.detachFd();
                rawOut = outFd.detachFd();
                final String modelPath = SandboxProto.fdPath(rawModel);
                final String configPath = SandboxProto.fdPath(rawConfig);
                final String outPath = SandboxProto.fdPath(rawOut);
                final int numFilaments = params.getInt(SandboxProto.KEY_NUM_FILAMENTS, 1);
                final int[] colors = params.getIntArray(SandboxProto.KEY_COLORS);
                final int calibMode = params.getInt(SandboxProto.KEY_CALIB_MODE, 0);
                final double calibStart = params.getDouble(SandboxProto.KEY_CALIB_START, 0);
                final double calibEnd = params.getDouble(SandboxProto.KEY_CALIB_END, 0);
                final double calibStep = params.getDouble(SandboxProto.KEY_CALIB_STEP, 0);
                synchronized (SLICE_LOCK) {
                    long modelPtr;
                    try {
                        // Touching Native runs its static init (System.loadLibrary
                        // of the APK-bundled engine). Linkage failure means the
                        // sandbox cannot run at all: infra, not content.
                        modelPtr = Native.model_read_from_file(
                                modelPath, "sandbox.3mf", 0);
                    } catch (LinkageError e) {
                        Log.e(TAG, "native libs unavailable in sandbox", e);
                        return result(SandboxProto.STATUS_ERROR_INFRA,
                                "native link failed: " + e.getMessage());
                    } catch (Slic3rRuntimeError e) {
                        return result(SandboxProto.STATUS_ERROR_CONTENT,
                                "sandbox read failed: " + e.getMessage());
                    }
                    try {
                        SliceListener forward = null;
                        if (callback != null) {
                            forward = new SliceListener() {
                                @Override
                                public void onProgress(int progress, String text) {
                                    try {
                                        callback.onProgress(progress, text);
                                    } catch (RemoteException gone) {
                                        Log.w(TAG, "client gone mid-slice");
                                    }
                                }
                            };
                        }
                        Native.model_slice(modelPtr, configPath, outPath, forward,
                                numFilaments, colors, calibMode,
                                calibStart, calibEnd, calibStep);
                    } catch (Slic3rRuntimeError e) {
                        return result(SandboxProto.STATUS_ERROR_CONTENT,
                                "sandbox slice failed: " + e.getMessage());
                    } finally {
                        try {
                            Native.model_release(modelPtr);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                return result(SandboxProto.STATUS_OK, "ok");
            } catch (Exception e) {
                Log.e(TAG, "sandbox job failed", e);
                return result(SandboxProto.STATUS_ERROR_CONTENT,
                        "sandbox error: " + e.getMessage());
            } finally {
                closeFd(rawModel);
                closeFd(rawConfig);
                closeFd(rawOut);
                try {
                    modelFd.close();
                } catch (Exception ignored) {
                }
                try {
                    configFd.close();
                } catch (Exception ignored) {
                }
                try {
                    outFd.close();
                } catch (Exception ignored) {
                }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
