package fi.valvontakamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Yön aikajana: liikemäärän käyrä, tallennetut videopätkät (sininen palkki alhaalla),
 * tapahtumat (oranssit merkit ylhäällä) ja kelauskohdistin.
 * Yksi sormi = kelaa, kaksi sormea = zoomaa.
 */
class TimelineView(c: Context) : View(c) {
    private var t0 = 0L
    private var t1 = 1L
    private var v0 = 0L
    private var v1 = 1L
    private var actT = LongArray(0)
    private var actV = FloatArray(0)
    private var maxV = 0.001f
    private var segs: List<Seg> = emptyList()
    private var events: List<Ev> = emptyList()
    var cursor = 0L
    var dragging = false; private set
    var onScrub: ((Long, Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 112, 67) }
    private val segPaint = Paint().apply { color = Color.rgb(79, 195, 247) }
    private val evPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 202, 40) }
    private val gridPaint = Paint().apply { color = Color.argb(60, 255, 255, 255); strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 11 * d }
    private val curPaint = Paint().apply { color = Color.WHITE; strokeWidth = 2 * d }
    private val curText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13 * d; isFakeBoldText = true
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val path = Path()

    fun setData(segs: List<Seg>, events: List<Ev>, actT: LongArray, actV: FloatArray, sessions: List<Long>) {
        this.segs = segs; this.events = events; this.actT = actT; this.actV = actV
        maxV = max(0.001f, actV.maxOrNull() ?: 0f)
        val all = ArrayList<Long>()
        segs.firstOrNull()?.let { all.add(it.start) }
        segs.lastOrNull()?.let { all.add(it.end) }
        if (actT.isNotEmpty()) { all.add(actT.first()); all.add(actT.last()) }
        all.addAll(sessions)
        events.forEach { all.add(it.start); all.add(it.end) }
        if (all.isEmpty()) { val now = System.currentTimeMillis(); all.add(now - 3_600_000); all.add(now) }
        val a = all.min(); val b = all.max()
        val pad = max(60_000L, (b - a) / 50)
        t0 = a - pad; t1 = b + pad
        v0 = t0; v1 = t1
        cursor = segs.firstOrNull()?.start ?: t0
        invalidate()
    }

    fun zoomAll() { v0 = t0; v1 = t1; invalidate() }

    fun ensureVisible(t: Long) {
        if (t in v0..v1) return
        val span = v1 - v0
        v0 = (t - span / 2).coerceIn(t0, max(t0, t1 - span))
        v1 = v0 + span
    }

    private fun xOf(t: Long): Float = ((t - v0).toDouble() / (v1 - v0) * width).toFloat()
    private fun tOf(x: Float): Long = v0 + (x.toDouble() / width * (v1 - v0)).toLong()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        c.drawColor(Color.rgb(21, 24, 29))
        val axisH = 18 * d
        val segH = 8 * d
        val top = 22 * d
        val plotBottom = h - axisH - segH - 3 * d

        // aika-akseli
        val span = v1 - v0
        val steps = longArrayOf(60_000, 2 * 60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000,
            3_600_000, 2 * 3_600_000, 3 * 3_600_000, 6 * 3_600_000)
        val step = steps.firstOrNull { span / it <= 7 } ?: (12 * 3_600_000L)
        val off = TimeZone.getDefault().getOffset(v0).toLong()
        var t = ((v0 + off) / step + 1) * step - off
        while (t < v1) {
            val x = xOf(t)
            c.drawLine(x, top, x, plotBottom + segH + 3 * d, gridPaint)
            c.drawText(Fmt.hm(t), x - 15 * d, h - 4 * d, textPaint)
            t += step
        }

        // liikekäyrä
        val bins = max(1, (w / (2 * d)).toInt())
        val binMs = span.toDouble() / bins
        var idx = lowerBound(actT, v0)
        path.reset()
        path.moveTo(0f, plotBottom)
        for (i in 0 until bins) {
            val bEnd = v0 + ((i + 1) * binMs).toLong()
            var m = 0f
            while (idx < actT.size && actT[idx] < bEnd) { if (actV[idx] > m) m = actV[idx]; idx++ }
            val y = plotBottom - sqrt(min(1f, m / maxV)) * (plotBottom - top)
            val x0 = i * w / bins
            val x1 = (i + 1) * w / bins
            path.lineTo(x0, y); path.lineTo(x1, y)
        }
        path.lineTo(w, plotBottom)
        path.close()
        c.drawPath(path, fillPaint)

        // videopätkät
        val sy = plotBottom + 3 * d
        for (s in segs) {
            val a = xOf(s.start); val b = xOf(s.end)
            if (b < 0 || a > w) continue
            c.drawRect(a, sy, max(b, a + 2), sy + segH, segPaint)
        }

        // tapahtumat
        for (e in events) {
            val x = xOf(e.start)
            if (x < -10 || x > w + 10) continue
            path.reset()
            path.moveTo(x - 5 * d, 2 * d); path.lineTo(x + 5 * d, 2 * d); path.lineTo(x, 10 * d); path.close()
            c.drawPath(path, evPaint)
        }

        // kohdistin
        val cx = xOf(cursor)
        if (cx in 0f..w) {
            c.drawLine(cx, 12 * d, cx, plotBottom + segH + 3 * d, curPaint)
            val txt = Fmt.time(cursor)
            val tw = curText.measureText(txt)
            val tx = (cx - tw / 2).coerceIn(0f, w - tw)
            c.drawText(txt, tx, top + 4 * d, curText)
        }
    }

    private fun lowerBound(a: LongArray, v: Long): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }
        return lo
    }

    private var scaling = false
    private val scaleDet = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val full = t1 - t0
            val minSpan = min(60_000L, full)
            val focusT = tOf(det.focusX)
            val span = ((v1 - v0) / det.scaleFactor).toLong().coerceIn(minSpan, full)
            val nv0 = (focusT - (det.focusX / width * span).toLong()).coerceIn(t0, t1 - span)
            v0 = nv0; v1 = nv0 + span
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        if (e.pointerCount > 1 || scaleDet.isInProgress) {
            scaling = true
            dragging = false
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scaling = false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                scrub(e.x, false)
            }
            MotionEvent.ACTION_MOVE -> if (dragging && !scaling) scrub(e.x, false)
            MotionEvent.ACTION_UP -> {
                if (dragging && !scaling) scrub(e.x, true)
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun scrub(x: Float, final: Boolean) {
        // reunassa siirrytään zoomatussa näkymässä eteen/taakse
        val span = v1 - v0
        if (span < t1 - t0) {
            val shift = span / 40
            if (x > width * 0.97f && v1 < t1) { v1 = min(t1, v1 + shift); v0 = v1 - span }
            else if (x < width * 0.03f && v0 > t0) { v0 = max(t0, v0 - shift); v1 = v0 + span }
        }
        cursor = tOf(x.coerceIn(0f, width.toFloat())).coerceIn(t0, t1)
        invalidate()
        onScrub?.invoke(cursor, final)
    }
}
