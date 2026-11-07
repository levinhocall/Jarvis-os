/*
 * Kotlin port of ideas from AbuZar-Ansarii/Jarvis (MIT, (c) 2026 AbuZar Ansarii).
 * Original is Flutter/Dart; this is a reimplementation for JARVIS OS. See docs/THIRD_PARTY_NOTICES.md.
 * Real measurements only: nothing is simulated or given a made-up score.
 */
package com.jarvis.assistant.ui.extras

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.ui.directives.DirectiveActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress

class NetworkAuditActivity : DirectiveActivity() {
    private lateinit var info: TextView
    private lateinit var scanOut: TextView
    private var ownIp: String? = null
    private var gateway: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        heading("Network Audit")
        label("Shows the network you are on right now. The optional scan only pings addresses in your own /24 subnet; it never probes ports or other networks.", 12f)
        info = label("")
        scanOut = label("")
        action("Refresh") { load() }
        action("Scan my subnet for live hosts") { b ->
            val ip = ownIp
            if (ip == null) { notice("No IPv4 network found."); return@action }
            b.isEnabled = false
            scanOut.text = "Scanning..."
            lifecycleScope.launch {
                try { scanOut.text = scan(ip) }
                catch (c: CancellationException) { throw c }
                catch (e: Exception) { scanOut.text = "Scan failed: ${e.message}" }
                finally { b.isEnabled = true }
            }
        }
        load()
    }

    private fun load() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val lp = net?.let { cm.getLinkProperties(it) }
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        if (lp == null) { info.text = "No active network."; return }
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Other"
        }
        ownIp = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
        gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val dns = lp.dnsServers.joinToString { it.hostAddress ?: "?" }.ifEmpty { "none" }
        info.text = buildString {
            appendLine("Transport: $transport")
            appendLine("Your IPv4: ${ownIp ?: "none"}")
            appendLine("Gateway: ${gateway ?: "none"}")
            appendLine("DNS: $dns")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                appendLine("Private DNS: ${if (lp.isPrivateDnsActive) lp.privateDnsServerName ?: "automatic" else "off"}")
            append("Internet validated: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}")
        }
    }

    private suspend fun scan(ip: String): String = withContext(Dispatchers.IO) {
        val prefix = ip.substringBeforeLast('.')
        val gw = gateway
        var gwMs = -1L
        if (gw != null) {
            val t0 = SystemClock.elapsedRealtime()
            if (InetAddress.getByName(gw).isReachable(500)) gwMs = SystemClock.elapsedRealtime() - t0
        }
        val gate = Semaphore(48)
        val alive = (1..254).map { n ->
            async {
                val host = "$prefix.$n"
                if (host == ip) null
                else gate.withPermit { if (runCatching { InetAddress.getByName(host).isReachable(300) }.getOrDefault(false)) host else null }
            }
        }.awaitAll().filterNotNull()
        buildString {
            appendLine("Gateway latency: ${if (gwMs >= 0) "$gwMs ms" else "no reply"}")
            appendLine("Hosts that answered on $prefix.0/24: ${alive.size}")
            appendLine(alive.joinToString())
            append("Note: devices that ignore ping are not listed, so this is a lower bound.")
        }
    }
}
