package dev.jaronwilson.headphones

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The "sound wave": the EQ's frequency response, with one dot per band you drag up or down.
 * [onChange] fires while dragging (the caller debounces) and [onDone] when the finger lifts.
 */
@SuppressLint("ViewConstructor")
class EqCurveView(context: Context, private val onChange: (List<Double>) -> Unit, private val onDone: (List<Double>) -> Unit) : View(context) {
    private val bands = EqModel.bands(context)
    private val range = EqModel.gainMax(context)
    private val lo = EqModel.gainMin(context)
    var gains: MutableList<Double> = EqModel.gains(context).toMutableList()
        set(v) { field = v.toMutableList(); invalidate() }
    var active = EqModel.enabled(context)
        set(v) { field = v; invalidate() }
    private var drag = -1
    private val d = resources.displayMetrics.density

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1F2A3A"); strokeWidth = 1 * d }
    private val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33415A"); strokeWidth = 1 * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8AA6B4"); textSize = 10 * d; textAlign = Paint.Align.CENTER }
    private val value = Paint(text).apply { color = Color.parseColor("#E8EDF2"); textSize = 11 * d }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F1419") }

    private val pad get() = 14 * d
    private fun x(u: Double) = (pad + u * (width - 2 * pad)).toFloat()
    private fun y(db: Double) = (height / 2f - db / range * (height / 2f - pad)).toFloat()

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (190 * d).toInt())

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#121A25"))
        for (db in listOf(-12.0, -6.0, 6.0, 12.0)) if (abs(db) <= range) c.drawLine(pad, y(db), width - pad, y(db), grid)
        c.drawLine(pad, y(0.0), width - pad, y(0.0), zero)
        for (f in listOf(100.0, 1000.0, 10000.0)) c.drawLine(x(EqModel.uOf(f)), pad, x(EqModel.uOf(f)), height - pad, grid)
        val col = Color.parseColor(if (active) "#5FD0FF" else "#6B7D90")
        line.color = col
        fill.color = col; fill.alpha = 30
        val p = Path(); val area = Path()
        area.moveTo(x(0.0), y(0.0))
        for (i in 0..120) {
            val u = i / 120.0
            val db = EqModel.responseDb(bands, gains, EqModel.freqAt(u)).coerceIn(-range, range)
            if (i == 0) p.moveTo(x(u), y(db)) else p.lineTo(x(u), y(db))
            area.lineTo(x(u), y(db))
        }
        area.lineTo(x(1.0), y(0.0)); area.close()
        c.drawPath(area, fill); c.drawPath(p, line)
        for ((i, b) in bands.withIndex()) {
            val cx = x(EqModel.uOf(b.freq)); val cy = y(gains[i])
            c.drawCircle(cx, cy, 10 * d, dotFill); c.drawCircle(cx, cy, 10 * d, line)
            c.drawText(b.label, cx, height - pad / 2 + 4 * d, text)
            val g = gains[i]
            c.drawText((if (g > 0) "+" else "") + (if (g == g.toLong().toDouble()) g.toLong().toString() else g.toString()), cx, cy - 14 * d, value)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = bands.indices.minByOrNull { abs(x(EqModel.uOf(bands[it].freq)) - e.x) } ?: -1
                if (drag >= 0 && abs(x(EqModel.uOf(bands[drag].freq)) - e.x) > 36 * d) drag = -1
                if (drag >= 0) parent?.requestDisallowInterceptTouchEvent(true)   // don't scroll the page
                return drag >= 0
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                val db = ((height / 2f - e.y) / (height / 2f - pad) * range).toDouble()
                gains[drag] = Math.round(db.coerceIn(lo, range) * 2) / 2.0
                invalidate(); onChange(gains.toList())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (drag >= 0) {
                drag = -1; onDone(gains.toList())
            }
        }
        return true
    }
}
