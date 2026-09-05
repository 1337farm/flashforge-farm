package com.flashforge.farm.iroh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.IrohAndroid
import computer.iroh.presetN0
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

object IrohSpike {
    private const val TAG = "IrohSpike"
    private val ALPN = "farm-spike/0".toByteArray()

    fun bindAndLog(ctx: Context) {
        Thread({
            try {
                IrohAndroid.installAndroidContext(ctx.applicationContext)
                val id = runBlocking(Dispatchers.IO) {
                    val ep = Endpoint.bind(
                        EndpointOptions(preset = presetN0(), alpns = listOf(ALPN))
                    )
                    val endpointId = ep.id().toString()
                    ep.shutdown()
                    endpointId
                }
                Log.i(TAG, "iroh endpoint bound: $id")
                toast(ctx, "iroh endpoint: $id")
            } catch (t: Throwable) {
                Log.e(TAG, "iroh bind failed", t)
                toast(ctx, "iroh bind failed: ${t.message}")
            }
        }, "iroh-spike").start()
    }

    private fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }
}
