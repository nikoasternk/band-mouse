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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

class WearableMouseClient(context: Context, private val scope: CoroutineScope) {
    private val nodeApi = Wearable.getNodeApi(context)
    private val authApi = Wearable.getAuthApi(context)
    private val messageApi = Wearable.getMessageApi(context)
    private var activeNode: Node? = null
    private var handshakeDeferred: CompletableDeferred<Boolean>? = null

    private val messageListener = OnMessageReceivedListener { _, bytes ->
        try {
            var text = bytes.decodeToString().trim()
            if (text.startsWith("\"") && text.endsWith("\"")) {
                text = JSONTokener(text).nextValue().toString()
            }

            when {
                text.startsWith("m:") -> {
                    val parts = text.substring(2).split(",")
                    if (parts.size == 2) {
                        val dx = parts[0].toIntOrNull() ?: 0
                        val dy = parts[1].toIntOrNull() ?: 0
                        MouseAccessibilityService.instance?.moveCursor(dx, dy)
                    }
                    return@OnMessageReceivedListener
                }
                text == "c:tap" -> {
                    MouseAccessibilityService.instance?.performClick()
                    return@OnMessageReceivedListener
                }
                text == "c:back" -> {
                    MouseAccessibilityService.instance?.performBack()
                    return@OnMessageReceivedListener
                }
                text.startsWith("s:") -> {
                    val parts = text.substring(2).split(",")
                    if (parts.size == 2) {
                        val dx = parts[0].toIntOrNull() ?: 0
                        val dy = parts[1].toIntOrNull() ?: 0
                        MouseAccessibilityService.instance?.performScroll(dx, dy)
                    }
                    return@OnMessageReceivedListener
                }
            }

            if (text.startsWith("{")) {
                val packet = JSONObject(text)
                if (packet.optString("tag") == "handshake") {
                    val step = packet.optInt("step", 0)
                    if (step > 0) {
                        handshakeDeferred?.complete(true)
                    }
                    if (step < 2) {
                        val reply = JSONObject().put("tag", "handshake").put("step", step + 1).toString()
                        activeNode?.let { messageApi.sendMessage(it.id, reply.toByteArray(Charsets.UTF_8)) }
                    }
                }
            }
        } catch (_: Exception) {}
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
        scope.launch(Dispatchers.IO) {
            val connection = queryConnectedBand()
            if (connection.isFailure || activeNode == null) {
                withContext(Dispatchers.Main) { onError() }
                return@launch
            }

            val nodeId = activeNode!!.id
            val bandName = connection.getOrNull() ?: "Band 10"
            unregisterListener(nodeId)
            if (!registerListener(nodeId)) {
                withContext(Dispatchers.Main) { onError() }
                return@launch
            }

            launchTouchpadPage(nodeId)
            delay(700)

            handshakeDeferred = CompletableDeferred()
            val handshakeInit = JSONObject().put("tag", "handshake").put("step", 0).toString()

            for (attempt in 1..6) {
                sendPacket(nodeId, handshakeInit)
                val ack = withTimeoutOrNull(600) { handshakeDeferred?.await() }
                if (ack == true) break
            }

            withContext(Dispatchers.Main) {
                onConnected(bandName)
            }
        }
    }
}