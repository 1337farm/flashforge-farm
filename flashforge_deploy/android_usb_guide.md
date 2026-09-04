# Flashforge Iroh Deployment: Android USB Automation Guide

This structural guide provides a framework for automating the deployment of the ARMv7 Iroh node and its administrative bootstrap scripts to a FAT32 USB OTG drive directly from our native Android (Kotlin) app.

By utilizing Android's Storage Access Framework (SAF) and native HTTP download capabilities, the mobile app can securely download the pre-compiled binary and transfer all necessary text configuration files straight to the physical media.

## Architectural Overview

1. **Network Download**: Retrieve the latest ARMv7 Iroh binary from a secure source into the app's internal cache.
2. **OTG Drive Detection**: Prompt the user to connect a FAT32 USB drive via USB OTG.
3. **Storage Access Framework (SAF)**: Request user authorization to write to the root of the connected USB drive.
4. **File Operations**: Stream the downloaded binary and write the text files (`flashforge_init.sh`, `uninstall.sh`, and `mode.txt`) to the root of the USB drive using SAF's DocumentFile API.

## Step-by-Step Implementation Guide

### Step 1: Downloading the ARMv7 Binary

First, download the Iroh binary into the app's secure internal storage (`context.cacheDir` or `context.filesDir`). Avoid using external shared storage like the Downloads folder.

You can use standard networking libraries like OkHttp or Android's built-in `DownloadManager`.

```kotlin
// Example using a simple Kotlin coroutine and HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

suspend fun downloadIrohBinary(context: Context, downloadUrl: String): File? {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL(downloadUrl)
            val connection = url.openConnection()
            connection.connect()

            val cacheFile = File(context.cacheDir, "iroh_armv7")
            val input = connection.getInputStream()
            val output = FileOutputStream(cacheFile)

            input.use { i ->
                output.use { o ->
                    i.copyTo(o)
                }
            }
            cacheFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
```

### Step 2: Requesting Access to the USB Drive via SAF

Android enforces strict storage permissions. We must use `ACTION_OPEN_DOCUMENT_TREE` to allow the user to select the root directory of the connected USB drive.

```kotlin
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

// In your Activity or Fragment:
val openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
        // The user granted access to the directory
        // Persist permissions if necessary
        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)

        // Proceed to write files to this URI
        deployToUsbDrive(uri)
    }
}

fun promptUserForUsbDrive() {
    openDocumentTreeLauncher.launch(null)
}
```

### Step 3: Writing Files to the USB Root

Once you have the `Uri` representing the USB root, use `DocumentFile` to create the required files. We'll use ContentResolver to open output streams.

**Important Filenames on USB Root:**
1. `iroh` (The ARMv7 binary)
2. `flashforge_init.sh` (The main deployment script)
3. `uninstall.sh` (The removal script)
4. `mode.txt` (Contains either "RAM" or "PERSISTENT")

```kotlin
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

fun deployToUsbDrive(usbRootUri: Uri) {
    val documentFile = DocumentFile.fromTreeUri(requireContext(), usbRootUri)

    if (documentFile == null || !documentFile.isDirectory) {
        // Handle error: Invalid URI
        return
    }

    // Helper function to handle SAF file duplication by replacing the file if it exists
    fun createFileSafely(mimeType: String, displayName: String): DocumentFile? {
        val existingFile = documentFile.findFile(displayName)
        existingFile?.delete()
        return documentFile.createFile(mimeType, displayName)
    }

    // 1. Copy Iroh Binary
    val irohFile = createFileSafely("application/octet-stream", "iroh")
    val cachedIroh = File(requireContext().cacheDir, "iroh_armv7")
    if (irohFile != null && cachedIroh.exists()) {
        cachedIroh.inputStream().use { input ->
            requireContext().contentResolver.openOutputStream(irohFile.uri)?.use { output ->
                input.copyTo(output)
            }
        }
    }

    // 2. Write flashforge_init.sh
    val initScriptFile = createFileSafely("application/x-sh", "flashforge_init.sh")
    initScriptFile?.let { writeStringToFile(it.uri, getInitScriptContent()) }

    // 3. Write uninstall.sh
    val uninstallScriptFile = createFileSafely("application/x-sh", "uninstall.sh")
    uninstallScriptFile?.let { writeStringToFile(it.uri, getUninstallScriptContent()) }

    // 4. Write mode.txt (Example: "PERSISTENT")
    val modeFile = createFileSafely("text/plain", "mode.txt")
    modeFile?.let { writeStringToFile(it.uri, "PERSISTENT") }
}

private fun writeStringToFile(uri: Uri, content: String) {
    requireContext().contentResolver.openOutputStream(uri)?.use { output ->
        output.write(content.toByteArray())
    }
}

// Implement getInitScriptContent() and getUninstallScriptContent() to return
// the raw text of the shell scripts provided in this repository.
```

## Developer Notes
- **FAT32 Constraints:** Ensure the USB drive is formatted to FAT32, as embedded Linux bootloaders rarely support exFAT or NTFS out-of-the-box.
- **Line Endings:** When writing the shell scripts via Kotlin string literals, ensure they are written with Unix line endings (`\n`), not Windows (`\r\n`), as the embedded shell may fail to execute otherwise.
- **App Permissions:** Depending on your target API level, you don't need `WRITE_EXTERNAL_STORAGE` when using the Storage Access Framework, as the user explicitly grants access to the specific tree via the picker.
