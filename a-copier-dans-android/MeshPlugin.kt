package com.exemple.messagerie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.util.concurrent.ConcurrentHashMap

@CapacitorPlugin(name = "Mesh")
class MeshPlugin : Plugin() {
    private val service = "com.exemple.messagerie.mesh"
    private val peers = ConcurrentHashMap.newKeySet<String>()
    private var started = false
    private var myId = ""
    private val client get() = Nearby.getConnectionsClient(context)

    private fun needed(): List<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_ADVERTISE
            p += Manifest.permission.BLUETOOTH_CONNECT
            p += Manifest.permission.BLUETOOTH_SCAN
        } else p += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        return p
    }

    @PluginMethod
    fun start(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id manquant")
        val missing = needed().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            if (call.getBoolean("ask", false) == true)
                ActivityCompat.requestPermissions(activity, missing.toTypedArray(), 42)
            call.resolve(JSObject().put("ok", false))
            return
        }
        if (!started) {
            started = true
            myId = id
            client.startAdvertising(
                myId, service, lifecycle,
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            )
            client.startDiscovery(
                service, discovery,
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            )
        }
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun send(call: PluginCall) {
        val data = call.getString("data") ?: return call.reject("data manquant")
        if (peers.isNotEmpty())
            client.sendPayload(peers.toList(), Payload.fromBytes(data.toByteArray(Charsets.UTF_8)))
        call.resolve()
    }

    private fun peerEvent() = notifyListeners("peer", JSObject().put("n", peers.size))

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // un seul des deux appareils lance la connexion (évite les doublons)
            if (myId < info.endpointName && !peers.contains(endpointId))
                client.requestConnection(myId, endpointId, lifecycle)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloads)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) { peers.add(endpointId); peerEvent() }
        }
        override fun onDisconnected(endpointId: String) { peers.remove(endpointId); peerEvent() }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                notifyListeners("packet", JSObject().put("data", String(it, Charsets.UTF_8)))
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}

