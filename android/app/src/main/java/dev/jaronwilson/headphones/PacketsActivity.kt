package dev.jaronwilson.headphones

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Live view of the Bluetooth packets this app sends to and reads from the AirPods (AAP over L2CAP) and the
 * Crusher (AT over RFCOMM): time, direction, device, hex and what it means. Everything shown is already
 * masked by [PacketLog]; nothing is saved. The two Read buttons only ask (no setting is changed).
 */
class PacketsActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var shown = listOf<PacketLog.Entry>()
    private var seenVersion = -1L
    private var filter: String? = null                       // null = both devices
    private lateinit var list: ListView
    private lateinit var count: TextView
    private lateinit var pause: Button
    private lateinit var filterBtn: Button
    private val adapter = Adapter()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(22), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#0F1419"))
        }
        col.addView(TextView(this).apply { text = "Packets"; textSize = 22f; setTextColor(Color.parseColor("#E8EDF2")) })
        col.addView(TextView(this).apply {
            text = "What this app sends to and reads from your headphones, newest at the bottom. Addresses, names, " +
                "serial numbers and keys show as xx. Kept in memory only (last ${PacketLog.MAX})."
            textSize = 12f; setTextColor(Color.parseColor("#8AA6B4")); setPadding(0, dp(4), 0, dp(6))
        })
        fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, onTap: (Button) -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; textSize = 13f; setOnClickListener { onTap(this) }
        }
        val r1 = row()
        pause = btn("Pause") { PacketLog.paused = !PacketLog.paused; bindPause() }
        filterBtn = btn("Both") {
            filter = when (filter) { null -> PacketLog.AIRPODS; PacketLog.AIRPODS -> PacketLog.CRUSHER; else -> null }
            it.text = filter ?: "Both"; seenVersion = -1; refresh()
        }
        for (b in listOf(pause, btn("Clear") { PacketLog.clear(); refresh() }, filterBtn, btn("Copy") { copy() }))
            r1.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(r1)
        val r2 = row()
        r2.addView(btn("Read AirPods") { readAirPods() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r2.addView(btn("Read Crusher") { readCrusher() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(r2)
        count = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#8AA6B4")); setPadding(0, dp(4), 0, dp(4)) }
        col.addView(count)
        list = ListView(this).apply {
            adapter = this@PacketsActivity.adapter
            transcriptMode = AbsListView.TRANSCRIPT_MODE_NORMAL     // follows the bottom unless you scroll up
            isStackFromBottom = true
            divider = null
            setBackgroundColor(Color.parseColor("#0B1015"))
        }
        col.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(col)
        bindPause()
    }

    override fun onResume() { super.onResume(); tick() }
    override fun onPause() { super.onPause(); ui.removeCallbacksAndMessages(null) }

    private fun tick() { refresh(); ui.postDelayed({ tick() }, 300) }

    private fun bindPause() { pause.text = if (PacketLog.paused) "Resume" else "Pause" }

    private fun refresh() {
        if (PacketLog.version == seenVersion) return
        seenVersion = PacketLog.version
        shown = PacketLog.entries().filter { filter == null || it.device == filter }
        adapter.notifyDataSetChanged()
        count.text = "${shown.size} packet${if (shown.size == 1) "" else "s"}" +
            (if (filter != null) " (${filter} only)" else "") + (if (PacketLog.paused) ", paused" else "")
    }

    private fun copy() {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Headphones packets", PacketLog.asText(shown)))
        Toast.makeText(this, "Copied ${shown.size} packets (masked)", Toast.LENGTH_SHORT).show()
    }

    private fun say(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    /** Reopens the AAP channel and listens: handshake, notification request, and the AirPods' report. No changes. */
    private fun readAirPods() {
        if (!Config.hasAirPods(this)) { say("Pick your AirPods on the main screen first"); return }
        val app = applicationContext
        thread { runCatching { PhoneDirect.readBack(app) }.onFailure { say("AirPods: ${it.message}") } }
    }

    /** Only queries: AT.UIAUDIO? and AT.CRUSHER? (and the extras once per connection). */
    private fun readCrusher() {
        val app = applicationContext
        thread {
            val s = runCatching { CrusherDirect.state(app) }.getOrNull()
            if (s == null) say("The Crusher isn't connected to this phone")
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
            val tv = (convert as? TextView) ?: TextView(this@PacketsActivity).apply {
                textSize = 13f; setTextColor(Color.parseColor("#E8EDF2")); setPadding(dp(6), dp(6), dp(6), dp(6))
                layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            tv.text = format(shown[i])
            return tv
        }
    }

    private fun format(e: PacketLog.Entry): CharSequence {
        val sb = SpannableStringBuilder()
        fun span(s: String, vararg what: Any) {
            val start = sb.length; sb.append(s)
            for (w in what) sb.setSpan(w, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        span(PacketLog.time(e.t) + "  ", ForegroundColorSpan(Color.parseColor("#6B8496")), TypefaceSpan("monospace"))
        span(if (e.sent) "SENT " else "RECV ", ForegroundColorSpan(Color.parseColor(if (e.sent) "#FFB35F" else "#5FD0FF")),
            StyleSpan(Typeface.BOLD), TypefaceSpan("monospace"))
        span(e.device + "  ", ForegroundColorSpan(Color.parseColor(if (e.device == PacketLog.AIRPODS) "#C9B8FF" else "#99FFBB")))
        span(e.meaning, StyleSpan(Typeface.BOLD))
        e.text?.let { sb.append("\n"); span("\"$it\"", ForegroundColorSpan(Color.parseColor("#E8D9A0")), TypefaceSpan("monospace")) }
        sb.append("\n")
        span(e.hex, ForegroundColorSpan(Color.parseColor("#9FB3C2")), TypefaceSpan("monospace"))
        return sb
    }
}
