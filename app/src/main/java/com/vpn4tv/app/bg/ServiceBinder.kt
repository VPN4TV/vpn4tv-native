package com.vpn4tv.app.bg

import android.os.Binder
import androidx.lifecycle.MutableLiveData
import com.vpn4tv.app.aidl.IServiceCallback
import com.vpn4tv.app.constant.Status

class ServiceBinder(private val status: MutableLiveData<Status>) : Binder() {
    private val callbacks = mutableListOf<IServiceCallback>()

    fun registerCallback(callback: IServiceCallback) {
        callbacks.add(callback)
    }

    fun unregisterCallback(callback: IServiceCallback) {
        callbacks.remove(callback)
    }

    fun broadcast(block: (IServiceCallback) -> Unit) {
        callbacks.toList().forEach { callback ->
            runCatching { block(callback) }
        }
    }

    fun close() {
        callbacks.clear()
    }
}
