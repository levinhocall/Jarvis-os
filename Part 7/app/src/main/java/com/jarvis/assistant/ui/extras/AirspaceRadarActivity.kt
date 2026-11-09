/*
 * Kotlin port of ideas from AbuZar-Ansarii/Jarvis (MIT, (c) 2026 AbuZar Ansarii).
 * Original is Flutter/Dart; this is a reimplementation for JARVIS OS. See docs/THIRD_PARTY_NOTICES.md.
 * Real OpenSky data only; if the request fails the screen says so instead of inventing aircraft.
 */
package com.jarvis.assistant.ui.extras

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import com.jarvis.assistant.ui.directives.DirectiveActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AirspaceRadarActivity : DirectiveActivity() {
    private lateinit var out: TextView
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) refresh() else out.text = "Location permission denied."
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        heading("Airspace Radar")
        label("Lists aircraft near your last known location. A box of about 1.2 degrees around you (rounded, not exact) is sent to the public OpenSky Network when you press Scan. OpenSky may rate-limit anonymous use.", 12f)
        out = label("")
        action("Scan") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) refresh()
            else perm.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun refresh() {
        val loc = lastLocation()
        if (loc == null) { out.text = "No recent location. Open a maps app once, then scan again."; return }
        out.text = "Scanning..."
        lifecycleScope.launch {
            try { out.text = fetch(loc.latitude, loc.longitude) }
            catch (c: CancellationException) { throw c }
            catch (e: Exception) { out.text = "Radar unavailable: ${e.message}" }
        }
    }

    private suspend fun fetch(lat0: Double, lon0: Double): String = withContext(Dispatchers.IO) {
        // Round to 0.1 degree so the exact position is not shared.
        val lat = Math.round(lat0 * 10) / 10.0
        val lon = Math.round(lon0 * 10) / 10.0
        val url = "https://opensky-network.org/api/states/all?lamin=${lat - 0.6}&lamax=${lat + 0.6}&lomin=${lon - 0.6}&lomax=${lon + 0.6}"
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("OpenSky HTTP ${r.code}")
            val states = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject.getAsJsonArray("states")
                ?: return@withContext "No aircraft reported in this area right now."
            data class Plane(val call: String, val country: String, val km: Double, val altM: Double?, val kmh: Double?, val track: Double?, val ground: Boolean)
            val planes = states.mapNotNull { e ->
                val a = e.asJsonArray
                fun d(i: Int) = a.get(i).takeIf { !it.isJsonNull }?.asDouble
                val la = d(6) ?: return@mapNotNull null
                val lo = d(5) ?: return@mapNotNull null
                Plane(
                    a.get(1).takeIf { !it.isJsonNull }?.asString?.trim().orEmpty().ifEmpty { "unknown" },
                    a.get(2).takeIf { !it.isJsonNull }?.asString.orEmpty(),
                    haversineKm(lat0, lon0, la, lo), d(7), d(9)?.times(3.6), d(10),
                    a.get(8).takeIf { !it.isJsonNull }?.asBoolean ?: false
                )
            }.sortedBy { it.km }.take(10)
            if (planes.isEmpty()) return@withContext "No aircraft with a position fix right now."
            planes.joinToString("\n") { p ->
                "${p.call} (${p.country}) ${"%.0f".format(p.km)} km, " +
                    (if (p.ground) "on ground" else "alt ${p.altM?.let { "%.0f m".format(it) } ?: "?"}") +
                    ", ${p.kmh?.let { "%.0f km/h".format(it) } ?: "? km/h"}, heading ${p.track?.let { "%.0f".format(it) } ?: "?"}"
            }
        }
    }

    private fun haversineKm(a: Double, b: Double, c: Double, d: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(c - a); val dLon = Math.toRadians(d - b)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
