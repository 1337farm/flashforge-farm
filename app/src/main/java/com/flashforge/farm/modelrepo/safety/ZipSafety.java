package com.flashforge.farm.modelrepo.safety;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ZipSafety {
    private ZipSafety() {
    }

    public interface Handler {
        void file(String name, InputStream in, long claimedSize) throws Exception;
    }

    public static void walk(File zip, Handler handler) throws Exception {
        if (zip.length() > SafetyPolicy.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("zip too large");
        }
        ZipFile zf = new ZipFile(zip);
        try {
            int count = 0;
            long totalOut = 0;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                count++;
                if (count > SafetyPolicy.MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("too many zip entries");
                }
                if (e.isDirectory()) {
                    continue;
                }
                String name = PathSanitizer.clean(new File(e.getName()).getName());
                long claimed = e.getSize();
                if (claimed > SafetyPolicy.MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("zip entry too large: " + name);
                }
                InputStream in = new BoundedInputStream(zf.getInputStream(e),
                        SafetyPolicy.MAX_FILE_BYTES, "zip entry overflow: " + name);
                try {
                    CountingInputStream counted = new CountingInputStream(in);
                    handler.file(name, counted, claimed);
                    totalOut += counted.count();
                    if (totalOut > SafetyPolicy.MAX_ZIP_OUTPUT_BYTES) {
                        throw new IllegalArgumentException("zip output overflow");
                    }
                    long compressed = e.getCompressedSize();
                    if (compressed > 0 && counted.count() > 1024 * 1024
                            && (double) counted.count() / (double) compressed > SafetyPolicy.MAX_ZIP_RATIO) {
                        throw new IllegalArgumentException("zip bomb suspected: " + name);
                    }
                } finally {
                    in.close();
                }
            }
        } finally {
            zf.close();
        }
    }

    public static void extract(File zip, File destDir) throws Exception {
        destDir.mkdirs();
        walk(zip, new Handler() {
            @Override
            public void file(String name, InputStream in, long claimedSize) throws Exception {
                File out = PathSanitizer.child(destDir, name);
                out.getParentFile().mkdirs();
                OutputStream fos = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                } finally {
                    fos.close();
                }
            }
        });
    }

    private static class BoundedInputStream extends InputStream {
        private final InputStream in;
        private final long max;
        private final String msg;
        private long count;

        BoundedInputStream(InputStream in, long max, String msg) {
            this.in = in;
            this.max = max;
            this.msg = msg;
        }

        @Override
        public int read() throws java.io.IOException {
            int b = in.read();
            if (b != -1) {
                count++;
                if (count > max) {
                    throw new java.io.IOException(msg);
                }
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                count += n;
                if (count > max) {
                    throw new java.io.IOException(msg);
                }
            }
            return n;
        }

        @Override
        public void close() throws java.io.IOException {
            in.close();
        }
    }

    private static class CountingInputStream extends InputStream {
        private final InputStream in;
        private long count;

        CountingInputStream(InputStream in) {
            this.in = in;
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws java.io.IOException {
            int b = in.read();
            if (b != -1) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public void close() throws java.io.IOException {
        }
    }
}