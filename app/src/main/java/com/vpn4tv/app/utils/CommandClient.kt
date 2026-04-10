package com.vpn4tv.app.utils

import android.util.Log
import go.Seq
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import com.vpn4tv.app.ktx.toList
import kotlinx.coroutines.CoroutineScope

open class CommandClient(
    private val scope: CoroutineScope,
    private val connectionTypes: List<ConnectionType>,
    private val handler: Handler,
) {
    constructor(
        scope: CoroutineScope,
        connectionType: ConnectionType,
        handler: Handler,
    ) : this(scope, listOf(connectionType), handler)

    private val additionalHandlers = mutableListOf<Handler>()
    private var cachedGroups: MutableList<OutboundGroup>? = null

    fun addHandler(handler: Handler) {
        synchronized(additionalHandlers) {
            if (!additionalHandlers.contains(handler)) {
                additionalHandlers.add(handler)
                cachedGroups?.let { groups ->
                    handler.updateGroups(groups)
                }
            }
        }
    }

    fun removeHandler(handler: Handler) {
        synchronized(additionalHandlers) {
            additionalHandlers.remove(handler)
        }
    }

    private fun getAllHandlers(): List<Handler> = synchronized(additionalHandlers) {
        listOf(handler) + additionalHandlers
    }

    enum class ConnectionType {
        Status,
        Groups,
        Log,
        ClashMode,
        Connections,
    }

    interface Handler {
        fun onConnected() {}

        fun onDisconnected() {}

        fun updateStatus(status: StatusMessage) {}

        fun setDefaultLogLevel(level: Int) {}

        fun clearLogs() {}

        fun appendLogs(messages: List<String>) {}

        fun updateGroups(newGroups: MutableList<OutboundGroup>) {}


        fun initializeClashMode(modeList: List<String>, currentMode: String) {}

        fun updateClashMode(newMode: String) {}

        fun writeConnectionEvents(events: ConnectionEvents) {}
    }

    private var commandClient: CommandClient? = null
    private val clientHandler = ClientHandler()

    fun connect() {
        disconnect()
        val options = CommandClientOptions()
        connectionTypes.forEach { connectionType ->
            val command =
                when (connectionType) {
                    ConnectionType.Status -> Libbox.CommandStatus
                    ConnectionType.Groups -> Libbox.CommandGroup
                    ConnectionType.Log -> Libbox.CommandLog
                    ConnectionType.ClashMode -> Libbox.CommandClashMode
                    ConnectionType.Connections -> Libbox.CommandConnections
                }
            options.addCommand(command)
        }
        options.statusInterval = 1 * 1000 * 1000 * 1000
        val commandClient = CommandClient(clientHandler, options)
        try {
            commandClient.connect()
        } catch (e: Exception) {
            Log.d("CommandClient", "connect failed", e)
            return
        }
        this.commandClient = commandClient
    }

    fun disconnect() {
        commandClient?.apply {
            runCatching {
                disconnect()
            }
//            Seq.destroyRef(refnum)
        }
        commandClient = null
    }

    private inner class ClientHandler : CommandClientHandler {
        override fun clearLogs() {}
        override fun initializeClashMode(modeList: io.nekohasekai.libbox.StringIterator?, currentMode: String?) {}
        override fun setDefaultLogLevel(level: Int) {}
        override fun updateClashMode(newMode: String?) {}
        override fun writeConnectionEvents(events: io.nekohasekai.libbox.ConnectionEvents?) {}
        override fun writeLogs(messageList: io.nekohasekai.libbox.LogIterator?) {
            if (messageList == null) return
            val logs = mutableListOf<String>()
            while (messageList.hasNext()) {
                val entry = messageList.next()
                logs.add(entry.message ?: "")
            }
            if (logs.isNotEmpty()) {
                getAllHandlers().forEach { it.appendLogs(logs) }
            }
        }
        override fun writeStatus(message: io.nekohasekai.libbox.StatusMessage?) {
            if (message != null) getAllHandlers().forEach { it.updateStatus(message) }
        }

        override fun connected() {
            getAllHandlers().forEach { it.onConnected() }
            Log.d("CommandClient", "connected")
        }

        override fun disconnected(message: String?) {
            getAllHandlers().forEach { it.onDisconnected() }
            Log.d("CommandClient", "disconnected: $message")
        }

        override fun writeGroups(message: OutboundGroupIterator?) {
            if (message == null) {
                return
            }
            val groups = mutableListOf<OutboundGroup>()
            while (message.hasNext()) {
                groups.add(message.next())
            }
            cachedGroups = groups
            getAllHandlers().forEach { it.updateGroups(groups) }
        }
    }
}
