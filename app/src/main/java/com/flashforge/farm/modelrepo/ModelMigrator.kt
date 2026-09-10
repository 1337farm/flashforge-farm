package com.flashforge.farm.modelrepo

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream

class ModelMigrator(private val context: Context, private val transport: IrohModelTransport) {
    private val TAG = "ModelMigrator"

    interface Callback {
        fun onProgress(model: String, stage: String, percent: Int)
        fun onCompleted(model: String, modelHash: String)
        fun onError(model: String, error: String)
        fun onAllDone()
    }

    fun migrateAll(callback: Callback) {
        Thread {
            try {
                // Use SeedModels to get properly attributed and signed seed models
                val seedModels = SeedModels.getAll()
                for (seedModel in seedModels) {
                    try {
                        callback.onProgress(seedModel.fileName, "Reading", 10)
                        val file = File(File(context.filesDir, "models"), seedModel.fileName)
                        if (!file.exists()) {
                            callback.onError(seedModel.fileName, "File not found: models/" + seedModel.fileName)
                            continue
                        }
                        val data = readAll(file)
                        callback.onProgress(seedModel.fileName, "Publishing", 50)
                        
                        // Use signed metadata from SeedModels
                        val signedMetadataJson = seedModel.toSignedMetadataJson()
                        
                        val datas = mutableListOf<ByteArray>()
                        datas.add(data)
                        val ticket = transport.publishModel(signedMetadataJson, datas)
                        callback.onCompleted(seedModel.fileName, ticket)
                    } catch (e: Exception) {
                        Log.e(TAG, "migrate failed", e)
                        callback.onError(seedModel.fileName, e.message ?: "failed")
                    }
                }
                callback.onAllDone()
            } catch (e: Exception) {
                Log.e(TAG, "Migration failed", e)
            }
        }.start()
    }

    private fun readAll(file: File): ByteArray {
        FileInputStream(file).use { fis ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32768)
            var n = fis.read(buf)
            while (n != -1) {
                out.write(buf, 0, n)
                n = fis.read(buf)
            }
            return out.toByteArray()
        }
    }

    companion object {
        // Legacy MODELS list kept for backward compatibility
        // New code should use SeedModels.getAll() for properly attributed and signed models
        @Deprecated("Use SeedModels.getAll() for signed seed models")
        private val MODELS = listOf<String>()
    }
}
