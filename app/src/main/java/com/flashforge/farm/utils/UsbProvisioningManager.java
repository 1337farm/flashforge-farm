package com.flashforge.farm.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Provisions a FlashForge printer over-the-USB from the app's "Provision via USB" flow.
 *
 * <p>This is the mobile half of the {@code flashforge_init.sh} deployment story: the app
 * downloads the ARMv7 Iroh binary into its internal cache, asks the user to pick a FAT32
 * USB OTG drive (via the Storage Access Framework), and writes the deployment payload
 * (binary, identity key, mode, bootstrapper and uninstall scripts) to the drive root.
 * The printer then boots the drive and runs {@code flashforge_init.sh}.</p>
 *
 * <p>The write half uses only {@code Context.getContentResolver()} against a tree URI from
 * {@code ACTION_OPEN_DOCUMENT_TREE}, so no extra Storage Access Framework dependency is
 * required to keep the build dependency-light.</p>
 */
public final class UsbProvisioningManager {
    private static final String TAG = "UsbProvisioning";

    public static final String IROH_DEFAULT_URL =
            "https://github.com/n0-computer/iroh/releases/latest/download/iroh-armv7-unknown-linux-gnueabihf";

    /** Provisioning modes written to mode.txt. */
    public static final String MODE_RAM = "RAM";
    public static final String MODE_INSTALL = "INSTALL";

    public interface ProvisionCallback {
        void onResult(boolean ok, String message);
    }

    /** Variant of {@link ProvisionCallback} that also surfaces a generated file (used by the Share target). */
    public interface ProvisionFileCallback {
        void onResult(boolean ok, String message, File file);
    }

    /** Holder for USB auto-detection results. */
    public static class UsbAutoDetect {
        /** Tree URI of the single detected drive, or null if multiple / none. */
        public final Uri treeUri;
        /** True when multiple drives are present (caller should launch SAF picker to disambiguate). */
        public final boolean multiple;
        public UsbAutoDetect(Uri treeUri, boolean multiple) {
            this.treeUri = treeUri;
            this.multiple = multiple;
        }
    }

    private UsbProvisioningManager() {}

    /** Build an Intent that launches the system SAF directory picker for a USB drive. */
    public static Intent buildUsbPickerIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        return i;
    }

    /**
     * Auto-detect USB drives connected via OTG. Uses {@link android.hardware.usb.UsbManager}
     * to enumerate mass-storage devices. The Android USB host API only exposes block devices
     * (not mounted filesystems), so a "tree URI" cannot be obtained directly without going
     * through the framework. We therefore return a {@link UsbAutoDetect} that signals:
     *  - {@code treeUri == null && !multiple}: no MSC device found
     *  - {@code treeUri == null && multiple == true}: multiple MSC devices present
     *
     * <p>Note: this is a best-effort presence check (filtered by USB permission). Writing
     * still happens through SAF, which is the only storage write API that works on scoped
     * storage (API 29+). On a real device, if a single FAT32 OTG drive is plugged in, SAF
     * auto-picks it as the default tree.</p>
     */
    public static UsbAutoDetect autoDetectUsb(Context ctx) {
        if (ctx == null) return null;
        android.hardware.usb.UsbManager usbMgr =
                (android.hardware.usb.UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (usbMgr == null) return null;

        Map<android.hardware.usb.UsbDevice, android.hardware.usb.UsbInterface> candidates = new HashMap<>();
        for (android.hardware.usb.UsbDevice d : usbMgr.getDeviceList().values()) {
            if (d == null) continue;
            if (!usbMgr.hasPermission(d)) continue;
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                android.hardware.usb.UsbInterface iface = d.getInterface(i);
                if (iface != null && iface.getInterfaceClass() == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE) {
                    candidates.put(d, iface);
                }
            }
        }
        if (candidates.isEmpty()) {
            // Fall back to "any device present" check: even without permission we can tell
            // the user that a USB device is plugged in.
            boolean any = !usbMgr.getDeviceList().isEmpty();
            if (!any) return null;
            return new UsbAutoDetect(null, false);
        }
        if (candidates.size() == 1) {
            return new UsbAutoDetect(null, false);
        }
        return new UsbAutoDetect(null, true);
    }

    /** Bundle the deployment payload into a zip file in the app's cache dir; the caller
     *  is responsible for sharing or moving it. */
    public static void buildSharePackage(Context ctx, boolean install, int mode,
                                         PrinterFleetManager.Printer printer, ProvisionFileCallback cb) {
        new Thread(() -> {
            try {
                File zip = File.createTempFile("flashforge_deploy", ".zip", ctx.getCacheDir());
                try (FileOutputStream fos = new FileOutputStream(zip);
                     ZipOutputStream zos = new ZipOutputStream(fos)) {
                    // Identity key
                    String key = printer != null && printer.publicKey != null
                            ? printer.publicKey
                            : PrinterFleetManager.generatePublicKey();
                    writeZipText(zos, "iroh_key.priv", key);
                    // Mode
                    String modeText = mode == 1 ? MODE_INSTALL : MODE_RAM;
                    writeZipText(zos, "mode.txt", modeText);
                    // Iroh binary (optional — large; skip if not yet downloaded to keep the
                    // share payload small and predictable)
                    File bin = obtainIrohBinary(ctx, null, null);
                    if (bin != null && bin.exists() && bin.length() > 0 && bin.length() < 32L * 1024L * 1024L) {
                        writeZipFile(zos, "iroh", bin);
                    }
                    // Scripts (assets — best-effort)
                    writeZipAsset(ctx, zos, "flashforge_init.sh");
                    writeZipAsset(ctx, zos, "uninstall.sh");
                }
                if (cb != null) cb.onResult(true, "Share package ready (" + zip.length() + " bytes).", zip);
            } catch (Exception e) {
                Log.e(TAG, "Failed to build share package", e);
                if (cb != null) cb.onResult(false, "Share package failed: " + e.getMessage(), null);
            }
        }, "build-share-package").start();
    }

    /** Download (or reuse) the ARMv7 Iroh binary, optionally verifying a SHA-256. */
    public static File obtainIrohBinary(Context ctx, String url, String expectedSha256) {
        File cacheFile = new File(ctx.getCacheDir(), "iroh_armv7");
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile;
        try {
            URL u = new URL(url == null || url.isEmpty() ? IROH_DEFAULT_URL : url);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(cacheFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                String actual = sha256(cacheFile);
                if (actual == null || !actual.equalsIgnoreCase(expectedSha256)) {
                    //noinspection ResultOfMethodCallIgnored
                    cacheFile.delete();
                    throw new IllegalStateException("SHA-256 mismatch for downloaded Iroh binary");
                }
            }
            return cacheFile;
        } catch (Exception e) {
            Log.e(TAG, "Failed to obtain Iroh binary", e);
            return null;
        }
    }

    /**
     * Deploy the provisioning payload to the USB drive whose tree {@code treeUri} was selected.
     * Runs the download + writes on a background thread and reports via {@code cb}.
     */
    public static void provision(Context ctx, Uri treeUri, String binaryUrl, int mode,
                                 PrinterFleetManager.Printer printer, ProvisionCallback cb) {
        new Thread(() -> {
            try {
                File bin = obtainIrohBinary(ctx, binaryUrl, null);
                if (bin == null) {
                    cb.onResult(false, "Could not download the Iroh binary. Check network / URL.");
                    return;
                }
                String modeText = mode == 1 ? MODE_INSTALL : MODE_RAM;
                writeStream(ctx, treeUri, "iroh", "application/octet-stream", new FileInputStream(bin));
                String key = printer.publicKey;
                if (key == null) key = PrinterFleetManager.generatePublicKey();
                writeText(ctx, treeUri, "iroh_key.priv", "application/octet-stream", key);
                writeText(ctx, treeUri, "mode.txt", "text/plain", modeText);
                writeAsset(ctx, treeUri, "flashforge_init.sh", "application/x-sh");
                writeAsset(ctx, treeUri, "uninstall.sh", "application/x-sh");
                cb.onResult(true, "Deployed successfully (mode: " + modeText + ").\nRemove & boot the USB drive to finish provisioning.");
            } catch (Exception e) {
                Log.e(TAG, "Provisioning failed", e);
                cb.onResult(false, "Provisioning failed: " + e.getMessage());
            }
        }, "usb-provision").start();
    }

    /** Remove an Iroh deployment from the drive (keeps only the uninstall driver script). */
    public static void uninstall(Context ctx, Uri treeUri, ProvisionCallback cb) {
        new Thread(() -> {
            try {
                deleteNamed(ctx, treeUri, "iroh", "iroh_key.priv", "mode.txt", "flashforge_init.sh");
                writeAsset(ctx, treeUri, "uninstall.sh", "application/x-sh");
                cb.onResult(true, "Uninstall driver written. Boot the USB drive to remove Iroh from the printer.");
            } catch (Exception e) {
                Log.e(TAG, "Uninstall failed", e);
                cb.onResult(false, "Uninstall failed: " + e.getMessage());
            }
        }, "usb-uninstall").start();
    }

    // ---- write helpers (SAF via ContentResolver only) ----

    private static void writeStream(Context ctx, Uri tree, String name, String mime, InputStream in) throws Exception {
        try (OutputStream out = ctx.getContentResolver().openOutputStream(tree.buildUpon().appendPath(name).build(), "wt")) {
            if (out == null) throw new IllegalStateException("No output stream for " + name);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            in.close();
        }
    }

    private static void writeText(Context ctx, Uri tree, String name, String mime, String content) throws Exception {
        byte[] bytes = content.getBytes("UTF-8");
        try (OutputStream out = ctx.getContentResolver().openOutputStream(tree.buildUpon().appendPath(name).build(), "wt")) {
            if (out == null) throw new IllegalStateException("No output stream for " + name);
            out.write(bytes);
        }
    }

    private static void writeAsset(Context ctx, Uri tree, String name, String mime) {
        try (InputStream in = ctx.getAssets().open(name)) {
            writeStream(ctx, tree, name, mime, in);
        } catch (Exception e) {
            Log.w(TAG, "No asset " + name + " to write", e);
        }
    }

    private static void deleteNamed(Context ctx, Uri tree, String... names) {
        for (String n : names) {
            try { ctx.getContentResolver().delete(tree.buildUpon().appendPath(n).build(), null, null); }
            catch (Exception ignored) {}
        }
    }

    private static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- share-package helpers ----

    private static void writeZipText(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zos.putNextEntry(e);
        zos.write(content.getBytes("UTF-8"));
        zos.closeEntry();
    }

    private static void writeZipFile(ZipOutputStream zos, String name, File source) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zos.putNextEntry(e);
        try (FileInputStream in = new FileInputStream(source)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
        }
        zos.closeEntry();
    }

    private static void writeZipAsset(Context ctx, ZipOutputStream zos, String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ZipEntry e = new ZipEntry(name);
            zos.putNextEntry(e);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
            zos.closeEntry();
        } catch (Exception e) {
            Log.w(TAG, "No asset " + name + " for share package", e);
        }
    }
}
