package com.githubbasedengineering.localbridge

import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

class MdnsPeerService(
    private val bindAddress: InetAddress,
    private val selfDeviceId: String,
    private val selfDeviceName: String,
    private val platformLabel: String,
    private val port: Int,
    private val onPeerResolved: (PeerDevice) -> Unit,
    private val onPeerRemoved: (String) -> Unit,
) : Closeable {
    private val serviceType = "_localbridge._tcp.local."
    private val serviceNameByDeviceId = ConcurrentHashMap<String, String>()
    private var jmDns: JmDNS? = null

    fun start() {
        val instanceName = "$selfDeviceName-${selfDeviceId.take(6)}"

        val dns = JmDNS.create(bindAddress, instanceName)
        val serviceInfo = ServiceInfo.create(
            serviceType,
            instanceName,
            port,
            0,
            0,
            mapOf(
                "deviceId" to selfDeviceId,
                "displayName" to selfDeviceName,
                "platform" to platformLabel,
            ),
        )

        dns.addServiceListener(serviceType, object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                dns.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                val removedId = serviceNameByDeviceId.entries.firstOrNull { it.value == event.name }?.key
                if (removedId != null && removedId != selfDeviceId) {
                    onPeerRemoved(removedId)
                }
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                val deviceId = info.getPropertyString("deviceId") ?: return
                if (deviceId == selfDeviceId) return

                val address = info.inet4Addresses.firstOrNull()?.hostAddress
                    ?: info.inetAddresses.firstOrNull()?.hostAddress
                    ?: return

                val peer = PeerDevice(
                    id = deviceId,
                    name = info.getPropertyString("displayName") ?: info.name,
                    host = address,
                    port = info.port,
                    platform = info.getPropertyString("platform") ?: "Unknown",
                    lastSeenMillis = System.currentTimeMillis(),
                )
                serviceNameByDeviceId[deviceId] = event.name
                onPeerResolved(peer)
            }
        })

        dns.registerService(serviceInfo)
        jmDns = dns
    }

    override fun close() {
        jmDns?.unregisterAllServices()
        jmDns?.close()
        jmDns = null
    }
}
