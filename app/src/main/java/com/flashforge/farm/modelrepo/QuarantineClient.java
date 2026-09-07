package com.flashforge.farm.modelrepo;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import com.flashforge.farm.modelrepo.verify.ModelVerifier;
import com.flashforge.farm.modelrepo.verify.Verdict;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class QuarantineClient {
    private static final long BIND_TIMEOUT_MS = 10000;

    private QuarantineClient() {
    }

    public static Verdict check(Context ctx, File file) {
        Verdict local = ModelVerifier.verifyForPublish(file);
        if (!local.allow) {
            return local;
        }
        Context app = ctx.getApplicationContext();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<IModelQuarantine> api = new AtomicReference<>();
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                api.set(IModelQuarantine.Stub.asInterface(service));
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                api.set(null);
            }
        };
        Intent intent = new Intent(app, ModelQuarantineService.class);
        boolean bound = false;
        try {
            bound = app.bindService(intent, conn, Context.BIND_AUTO_CREATE);
            if (!bound) {
                return local;
            }
            if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return local;
            }
            IModelQuarantine q = api.get();
            if (q == null) {
                return local;
            }
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_ONLY);
            Bundle res;
            try {
                res = q.quarantine(fd, file.getName(), file.length());
            } finally {
                try {
                    fd.close();
                } catch (Exception ignored) {
                }
            }
            if (res == null) {
                return local;
            }
            if (!res.getBoolean("allow", false)) {
                return Verdict.deny(Verdict.Reason.UNSAFE_TYPE,
                        res.getString("detail", "quarantined"));
            }
            return local;
        } catch (Exception e) {
            return local;
        } finally {
            if (bound) {
                try {
                    app.unbindService(conn);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static Verdict checkStream(Context ctx, String fileName,
            java.io.InputStream in) {
        String clean;
        try {
            clean = com.flashforge.farm.modelrepo.safety.PathSanitizer.clean(fileName);
        } catch (IllegalArgumentException bad) {
            return Verdict.deny(Verdict.Reason.UNSAFE_NAME, "bad name");
        }
        File staged = new File(ctx.getCacheDir(), "q_" + clean);
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(staged);
            try {
                byte[] buf = new byte[32768];
                int n;
                long total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > com.flashforge.farm.modelrepo.safety.SafetyPolicy.MAX_PARSE_BYTES) {
                        return Verdict.deny(Verdict.Reason.OVER_BUDGET, "stream too large");
                    }
                    fos.write(buf, 0, n);
                }
            } finally {
                fos.close();
            }
            return check(ctx, staged);
        } catch (Exception e) {
            return Verdict.deny(Verdict.Reason.BAD_METADATA, "quarantine failed");
        } finally {
            staged.delete();
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
