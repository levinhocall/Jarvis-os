package com.jarvis.assistant.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Native OSM map. Search/route results are sourced from public geocoding/routing services. */
class AmoledMapActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var destination: EditText
    private lateinit var actions: LinearLayout
    private lateinit var navigationButton: Button
    private var origin: GeoPoint? = null
    private var selectedDestination: GeoPoint? = null
    private var routeOverlay: Polyline? = null
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locateUser()
        else status.text = "Location permission denied. Search works, but route origin needs your location."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeManager.getThemeBgColor(
                com.jarvis.assistant.JarvisApp.instance.preferences.appTheme,
                com.jarvis.assistant.JarvisApp.instance.preferences.customThemeColor
            ))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        destination = EditText(this).apply {
            hint = "Search destination"
            singleLine = true
            setTextColor(color(R.attr.themeTextPrimary))
            setHintTextColor(color(R.attr.themeTextMuted))
        }
        root.addView(destination, LinearLayout.LayoutParams(-1, dp(52)))
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Search") { searchDestination() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("My location") { requestLocation() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Clear") { clearRoute() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        navigationButton = button("Open turn-by-turn navigation") { openNavigation() }.apply { visibility = View.GONE }
        root.addView(navigationButton, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            text = "Loading OpenStreetMap…"
            textSize = 12f
            setTextColor(color(R.attr.themeTextMuted))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        root.addView(status)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(true)
            // Start zoomed out until a real device location or search result is available.
            controller.setZoom(2.5)
        }
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "© OpenStreetMap contributors · Community geocoding/routing services may be rate-limited."
            textSize = 10f
            setTextColor(color(R.attr.themeTextMuted))
            setPadding(dp(4), dp(5), dp(4), 0)
            setOnClickListener {
                startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.openstreetmap.org/copyright")
                ))
            }
            contentDescription = "OpenStreetMap attribution and licensing"
        })
        setContentView(root)
        requestLocation()
    }

    private fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        locateUser()
    }

    @Suppress("MissingPermission")
    private fun locateUser() {
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { provider -> try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null } }
                .maxByOrNull { it.time }
            if (last == null) {
                status.text = "No recent location fix. Enable device location and try again."
                return
            }
            origin = GeoPoint(last.latitude, last.longitude)
            map.overlays.removeAll { it is Marker && it.title == "Your location" }
            map.overlays.add(Marker(map).apply {
                position = origin
                title = "Your location"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
            map.controller.animateTo(origin)
            status.text = "Current location acquired from Android location services."
            map.invalidate()
        } catch (error: Exception) {
            status.text = "Location unavailable: ${error.localizedMessage ?: "unknown error"}"
        }
    }

    private fun searchDestination() {
        val query = destination.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) { status.text = "Enter a destination to search."; return }
        status.text = "Searching destination…"
        lifecycleScope.launch {
            try {
                val point = withContext(Dispatchers.IO) {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val request = Request.Builder()
                        .url("https://nominatim.openstreetmap.org/search?q=$encoded&format=jsonv2&limit=1")
                        .header("User-Agent", "JarvisOS-Android/1.0 (destination search)")
                        .build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("Geocoding service returned HTTP ${response.code}")
                        val rows = org.json.JSONArray(response.body?.string().orEmpty())
                        if (rows.length() == 0) error("No matching destination found")
                        val row = rows.getJSONObject(0)
                        GeoPoint(row.getDouble("lat"), row.getDouble("lon")) to row.optString("display_name", query)
                    }
                }
                val (point, label) = point
                selectedDestination = point
                map.overlays.removeAll { it is Marker && it.title == "Destination" }
                map.overlays.add(Marker(map).apply {
                    position = point
                    title = "Destination"
                    snippet = label
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
                map.controller.animateTo(point)
                map.invalidate()
                status.text = "Destination found. Searching for a route…"
                requestRoute(point)
            } catch (error: Exception) {
                status.text = "Destination search failed: ${error.message ?: "service unavailable"}"
            }
        }
    }

    private fun requestRoute(target: GeoPoint) {
        val start = origin
        if (start == null) {
            status.text = "Destination marked. Grant location permission to calculate a route."
            return
        }
        lifecycleScope.launch {
            try {
                val points = withContext(Dispatchers.IO) {
                    val url = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${target.longitude},${target.latitude}?overview=full&geometries=geojson"
                    val request = Request.Builder().url(url).header("User-Agent", "JarvisOS-Android/1.0").build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("Routing service returned HTTP ${response.code}")
                        val json = JSONObject(response.body?.string().orEmpty())
                        val routes = json.optJSONArray("routes") ?: error("No route returned")
                        if (routes.length() == 0) error("No drivable route found")
                        val coordinates = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
                        (0 until coordinates.length()).map { index ->
                            val pair = coordinates.getJSONArray(index)
                            GeoPoint(pair.getDouble(1), pair.getDouble(0))
                        }
                    }
                }
                clearRoute()
                val route = Polyline().apply {
                    setPoints(points)
                    outlinePaint.strokeWidth = dp(5).toFloat()
                }
                routeOverlay = route
                map.overlays.add(route)
                navigationButton.visibility = View.VISIBLE
                map.invalidate()
                status.text = "Route loaded from OpenStreetMap routing service. Turn-by-turn guidance opens in your navigation app."
                val bounds = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
                map.zoomToBoundingBox(bounds, true, dp(36))
            } catch (error: Exception) {
                status.text = "Route unavailable: ${error.message ?: "routing service unavailable"}. Destination marker remains available."
            }
        }
    }

    private fun clearRoute() {
        routeOverlay?.let { map.overlays.remove(it) }
        routeOverlay = null
        if (::navigationButton.isInitialized) navigationButton.visibility = View.GONE
        map.invalidate()
    }

    private fun openNavigation() {
        val target = selectedDestination ?: run {
            status.text = "Search for a destination first."
            return
        }
        val uri = android.net.Uri.parse("google.navigation:q=${target.latitude},${target.longitude}")
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            val webUri = android.net.Uri.parse("https://www.openstreetmap.org/?mlat=${target.latitude}&mlon=${target.longitude}#map=16/${target.latitude}/${target.longitude}")
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, webUri)) }
            catch (_: Exception) { status.text = "No compatible navigation app found." }
        }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun color(attr: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }
    override fun onDestroy() { map.onDetach(); http.dispatcher.cancelAll(); super.onDestroy() }
}
