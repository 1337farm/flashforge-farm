package com.flashforge.farm.modelrepo;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.flashforge.farm.modelrepo.safety.PathSanitizer;
import com.flashforge.farm.modelrepo.safety.SafetyPolicy;

import java.io.FileInputStream;
import java.io.InputStream;

public class ModelQuarantineService extends Service {
    private final IModelQuarantine.Stub binder = new IModelQuarantine.Stub() {
        @Override
        public Bundle quarantine(ParcelFileDescriptor fd, String fileName, long fileSize)
                throws RemoteException {
            Bundle out = new Bundle();
            out.putBoolean("allow", false);
            out.putBoolean("needsConfirm", false);
            out.putString("reason", "ERROR");
            out.putString("detail", "rejected");
            if (fd == null || fileName == null) {
                return out;
            }
            String clean;
            try {
                clean = PathSanitizer.clean(fileName);
            } catch (IllegalArgumentException bad) {
                out.putString("reason", "UNSAFE_NAME");
                out.putString("detail", bad.getMessage() == null ? "bad name" : bad.getMessage());
                return out;
            }
            if (fileSize < 0 || fileSize > SafetyPolicy.MAX_PARSE_BYTES) {
                out.putString("reason", "OVER_BUDGET");
                out.putString("detail", "size out of bounds");
                return out;
            }
            InputStream in = null;
            try {
                in = new FileInputStream(fd.getFileDescriptor());
                String sniffed = ModelSafety.sniffStream(in, fileSize);
                String ext = ModelSafety.extensionOf(clean);
                if (!ModelSafety.extensionMatchesSniff(ext, sniffed)) {
                    out.putString("reason", "UNSAFE_TYPE");
                    out.putString("detail", "content is " + sniffed);
                    return out;
                }
                if ((sniffed.equals("stl-binary") || sniffed.equals("stl-ascii"))
                        && fileSize >= 84) {
                    long facets = sniffed.equals("stl-binary")
                            ? Math.max(0, (fileSize - 84) / 50)
                            : 0;
                    if (facets > SafetyPolicy.MAX_FACETS) {
                        out.putString("reason", "OVER_BUDGET");
                        out.putString("detail", "too many facets");
                        return out;
                    }
                }
                out.putBoolean("allow", true);
                out.putString("reason", "OK");
                out.putString("detail", sniffed);
                return out;
            } catch (Exception e) {
                out.putString("reason", "ERROR");
                out.putString("detail", "unreadable");
                return out;
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
                try {
                    fd.close();
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
