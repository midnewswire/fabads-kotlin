package com.fabads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Small helpers shared by the network modules. */
object AdUtils {
    const val TAG = "FabAds"

    private val main = Handler(Looper.getMainLooper())

    fun log(network: String, message: String?) {
        if (FabAds.debug) Log.d(TAG, "$network: $message")
    }

    /** Runs [block] on the main thread, right away when already there. */
    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** Wraps a callback so SDKs that report the same ad twice (an error after close, etc.) only answer once. */
    fun <T> once(callback: (T) -> Unit): (T) -> Unit {
        var called = false
        return { value ->
            onMain {
                if (!called) {
                    called = true
                    callback(value)
                }
            }
        }
    }

    /** [once] for callbacks without a value. */
    fun onceUnit(callback: () -> Unit): () -> Unit {
        val wrapped = once<Unit> { callback() }
        return { wrapped(Unit) }
    }
}
