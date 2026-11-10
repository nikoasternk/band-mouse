package com.band.mouse.transport

import android.content.Context
import com.band.mouse.service.MouseAccessibilityService
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

class WearableMouseClient private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: WearableMouseClient? = null

        fun getInstance(context: Context): WearableMouseClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WearableMouseClient(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nodeApi = Wearable.getNodeApi(appContext)
    private val authApi = Wearable.getAuthApi(appContext)
    private val messageApi = Wearable.getMessageApi(appContext)
    private var activeNode: Node? = null
    private var isListenerRegistered = false
    private var handshakeDeferred: CompletableDeferred<Boolean>? = null

    private val messageListener = OnMessageReceivedListener { _, bytes ->
        try {
            var text = bytes.decodeToString().trim()
            while (text.startsWith("\"") && text.endsWith("\"")) {
                text = JSONTokener(text).nextValue().toString()
            }

            val packet = JSONObject(text)
            when (packet.optString("tag")) {
                "mouse" -> {
                    when (packet.optString("act")) {
                        "m" -> {
                            val dx = packet.optInt("dx", 0)
                            val dy = packet.optInt("dy", 0)
                            MouseAccessibilityService.instance?.moveCursor(dx, dy)
                        }
                        "tap" -> {
                            MouseAccessibilityService.instance?.performClick()
                        }
                        "s" -> {
                            val dx = packet.optInt("dx", 0)
                            val dy = packet.optInt("dy", 0)
                            MouseAccessibilityService.instance?.performScroll(dx, dy)
                        }
                        "back" -> {
                            MouseAccessibilityService.instance?.performBack()
                        }
                        "home" -> {
                            MouseAccessibilityService.instance?.performHome()
                        }
                        "recents" -> {
                            MouseAccessibilityService.instance?.performRecents()
                        }
                        "stealth" -> {
                            MouseAccessibilityService.instance?.toggleStealthMode()
                        }
                        "ping" -> {
                            MouseAccessibilityService.instance?.onWatchHeartbeat()
                        }
                        "state" -> {
                            val isOpen = packet.optBoolean("open", false)
                            MouseAccessibilityService.instance?.onWatchStateChanged(isOpen)
                        }
                    }
                }
                "handshake" -> {
                    val step = packet.optInt("step", 0)
                    if (step > 0) {
                        handshakeDeferred?.complete(true)
                        MouseAccessibilityService.instance?.onWatchStateChanged(true)
                    }
                    if (step < 2) {
                        val reply = JSONObject().put("tag", "handshake").put("step", step + 1).toString()
                        activeNode?.let { messageApi.sendMessage(it.id, reply.toByteArray(Charsets.UTF_8)) }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun ensureListening() {
        clientScope.launch {
            val conn = queryConnectedBand()
            val node = activeNode
            if (conn.isSuccess && node != null && !isListenerRegistered) {
                unregisterListener(node.id)
                isListenerRegistered = registerListener(node.id)
            }
        }
    }

    suspend fun queryConnectedBand(): Result<String> {
        val deferred = CompletableDeferred<Result<String>>()
        nodeApi.connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isNullOrEmpty()) {
                deferred.complete(Result.failure(Exception()))
            } else {
                val node = nodes[0]
                activeNode = node
                val permissions = arrayOf(Permission.DEVICE_MANAGER)
                authApi.checkPermissions(node.id, permissions).addOnSuccessListener { granted ->
                    if (granted.isNotEmpty() && !granted[0]) {
                        authApi.requestPermission(node.id, Permission.DEVICE_MANAGER)
                            .addOnSuccessListener {
                                deferred.complete(Result.success(node.name ?: "Band 10"))
                            }
                            .addOnFailureListener {
                                deferred.complete(Result.success(node.name ?: "Band 10"))
                            }
                    } else {
                        deferred.complete(Result.success(node.name ?: "Band 10"))
                    }
                }.addOnFailureListener {
                    deferred.complete(Result.success(node.name ?: "Band 10"))
                }
            }
        }.addOnFailureListener { e ->
            deferred.complete(Result.failure(e))
        }
        return deferred.await()
    }

    private suspend fun unregisterListener(nodeId: String) {
        val deferred = CompletableDeferred<Unit>()
        messageApi.removeListener(nodeId)
            .addOnSuccessListener { deferred.complete(Unit) }
            .addOnFailureListener { deferred.complete(Unit) }
        deferred.await()
    }

    private suspend fun registerListener(nodeId: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        messageApi.addListener(nodeId, messageListener)
            .addOnSuccessListener { deferred.complete(true) }
            .addOnFailureListener { deferred.complete(false) }
        return deferred.await()
    }

    private suspend fun launchTouchpadPage(nodeId: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        nodeApi.launchWearApp(nodeId, "/pages/touchpad")
            .addOnSuccessListener { deferred.complete(true) }
            .addOnFailureListener { deferred.complete(false) }
        return deferred.await()
    }

    private suspend fun sendPacket(nodeId: String, jsonString: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        messageApi.sendMessage(nodeId, jsonString.toByteArray(Charsets.UTF_8))
            .addOnSuccessListener { deferred.complete(true) }
            .addOnFailureListener { deferred.complete(false) }
        return deferred.await()
    }

    fun startMouseSession(
        onConnected: (String) -> Unit,
        onError: () -> Unit
    ) {
        clientScope.launch {
            val connection = queryConnectedBand()
            if (connection.isFailure || activeNode == null) {
                withContext(Dispatchers.Main) { onError() }
                return@launch
            }

            val nodeId = activeNode!!.id
            val bandName = connection.getOrNull() ?: "Band 10"
            unregisterListener(nodeId)
            isListenerRegistered = registerListener(nodeId)
            if (!isListenerRegistered) {
                withContext(Dispatchers.Main) { onError() }
                return@launch
            }

            launchTouchpadPage(nodeId)
            delay(800)

            handshakeDeferred = CompletableDeferred()
            val handshakeInit = JSONObject().put("tag", "handshake").put("step", 0).toString()

            var connected = false
            for (attempt in 1..10) {
                sendPacket(nodeId, handshakeInit)
                val ack = withTimeoutOrNull(650) { handshakeDeferred?.await() }
                if (ack == true) {
                    connected = true
                    break
                }
            }

            withContext(Dispatchers.Main) {
                if (connected) {
                    MouseAccessibilityService.instance?.onWatchStateChanged(true)
                    onConnected(bandName)
                } else {
                    onError()
                }
            }
        }
    }
}
