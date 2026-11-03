package com.jarvis.assistant.ui.map

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.osmdroid.util.GeoPoint
import kotlin.math.*

/** Actual route/GPS vectors on black; no invented streets or raster tile requests. */
class TacticalVectorView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var route: List<GeoPoint> = emptyList()
    private var origin: GeoPoint? = null
    private var destination: GeoPoint? = null
    private var dirty = true
    private var originPixel: PointF? = null
    private var destinationPixel: PointF? = null
    private var zoom = 1f
    private var offsetX = 0f; private var offsetY = 0f
    private var touchX = 0f; private var touchY = 0f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean { zoom = (zoom * detector.scaleFactor).coerceIn(0.5f, 12f); invalidate(); return true }
    })
    init { contentDescription = "Black vector route map. Drag to pan and pinch to zoom."; setBackgroundColor(Color.BLACK); isFocusable = true }
    fun show(origin: GeoPoint?, destination: GeoPoint?, points: List<GeoPoint>) {
        this.origin = origin; this.destination = destination; route = points; dirty = true; invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { dirty = true }
    private fun rebuildGeometry() {
        dirty = false
        path.reset(); originPixel = null; destinationPixel = null
        val points = route + listOfNotNull(origin, destination)
        if (points.isEmpty()) return
        val minLat = points.minOf { it.latitude }; val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }; val maxLon = points.maxOf { it.longitude }
        val centerLat = (minLat + maxLat) / 2; val centerLon = (minLon + maxLon) / 2
        val lonFactor = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
        val spanX = ((maxLon - minLon) * lonFactor).coerceAtLeast(0.005)
        val spanY = (maxLat - minLat).coerceAtLeast(0.005)
        val unit = min((width - 64).coerceAtLeast(1) / spanX, (height - 110).coerceAtLeast(1) / spanY)
        fun project(p: GeoPoint) = PointF((width / 2 + (p.longitude - centerLon) * lonFactor * unit).toFloat(), (height / 2 - (p.latitude - centerLat) * unit).toFloat())
        route.forEachIndexed { i, point -> val p = project(point); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        originPixel = origin?.let(::project); destinationPixel = destination?.let(::project)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dirty) rebuildGeometry()
        paint.color = Color.WHITE; paint.textSize = 13 * resources.displayMetrics.scaledDensity; paint.style = Paint.Style.FILL
        if (route.isEmpty() && origin == null && destination == null) {
            canvas.drawText("VECTOR GUIDE · waiting for location", 18f, 40f, paint); return
        }
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(zoom, zoom, width / 2f, height / 2f)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 5 * resources.displayMetrics.density / zoom; paint.color = Color.CYAN
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        originPixel?.let { paint.color = Color.CYAN; canvas.drawCircle(it.x, it.y, 10f / zoom, paint) }
        destinationPixel?.let { paint.color = Color.WHITE; canvas.drawCircle(it.x, it.y, 12f / zoom, paint) }
        canvas.restore()
        paint.color = Color.WHITE
        canvas.drawText("N ↑   VECTOR ROUTE · pinch / drag", 18f, height - 24f, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touchX = event.x; touchY = event.y; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { if (!scale.isInProgress && event.pointerCount == 1) { offsetX += event.x - touchX; offsetY += event.y - touchY; invalidate() }; touchX = event.x; touchY = event.y }
            MotionEvent.ACTION_UP -> { parent.requestDisallowInterceptTouchEvent(false); performClick() }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
