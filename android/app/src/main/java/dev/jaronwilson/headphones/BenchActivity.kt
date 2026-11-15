package dev.jaronwilson.headphones

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hidden benchmark for Filtered hearing (no launcher icon; only the shell can open it, it needs the DUMP
 * permission): `adb shell am start -n dev.jaronwilson.headphones/.BenchActivity`.
 *
 * Everything runs on synthetic audio in memory. Nothing is played or recorded, no Bluetooth, no AirPods.
 * Results show on the screen and in logcat (tag FHBench).
 */
class BenchActivity : Activity() {
    private lateinit var out: TextView
    private val sb = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 12f; setTextColor(Color.WHITE); setPadding(30, 60, 30, 30) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(out) })
        say("Filtered hearing benchmark (in memory, plays nothing)\n${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        thread(name = "fh-bench") { runCatching { run() }.onFailure { say("FAILED: $it"); Log.e(TAG, "bench", it) } }
    }

    private fun say(s: String) {
        Log.i(TAG, s)
        runOnUiThread { sb.append(s).append('\n'); out.text = sb }
    }

    /** 64 kHz test audio: a voiced "talker" (pulse train through three formants, syllables and pauses),
     *  a fan-like noise and keyboard-like clicks. */
    private fun synth(secs: Double, rate: Int = 64_000, seed: Int = 1): DoubleArray {
        val r = Random(seed); val n = (secs * rate).toInt(); val x = DoubleArray(n)
        val f = doubleArrayOf(600.0, 1300.0, 2600.0); val y1 = DoubleArray(3); val y2 = DoubleArray(3)
        var ph = 0.0; var lp = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val syl = (t * 4).toInt(); val on = (syl % 5 != 4) && sin(PI * (t * 4 - syl)) > 0.2
            val f0 = 120 + 20 * sin(2 * PI * 0.7 * t)
            ph += f0 / rate; var v = 0.0
            if (ph >= 1) { ph -= 1; v = if (on) 1.0 else 0.0 }
            for (k in 0..2) {
                val rr = exp(-PI * 120 / rate); val a1 = 2 * rr * kotlin.math.cos(2 * PI * f[k] * (1 + 0.1 * sin(syl.toDouble())) / rate)
                val yk = (1 - rr) * v + a1 * y1[k] - rr * rr * y2[k]; y2[k] = y1[k]; y1[k] = yk; v = yk
            }
            lp = 0.95 * lp + 0.05 * (r.nextDouble() * 2 - 1)
            x[i] = 0.3 * v + 0.05 * lp
            if (r.nextDouble() < 6.0 / rate) for (j in 0 until minOf(1200, n - i)) x[i + j] += 0.05 * (r.nextDouble() * 2 - 1) * exp(-j / 200.0)
        }
        return x
    }

    private fun run() {
        val t0 = System.nanoTime()
        val dfnBytes = assets.open(OrtModels.DFN_ASSET).use { it.readBytes() }
        val spkBytes = assets.open(OrtModels.SPK_ASSET).use { it.readBytes() }
        val dfn = OrtModels.Dfn(dfnBytes)
        val spk = OrtModels.CamPlus(spkBytes)
        say("models loaded in ${(System.nanoTime() - t0) / 1_000_000} ms (DFN ${dfnBytes.size / 1024} KB, CAM++ ${spkBytes.size / 1024} KB)")

        // 1) DeepFilterNet3 alone, 10 ms hops at 48 kHz
        val hops = 2000
        val x48 = synth(hops * 0.01 + 0.1, 48_000)
        val fr = FloatArray(480); val o = FloatArray(480)
        for (h in 0 until 100) { for (i in 0 until 480) fr[i] = x48[h * 480 + i].toFloat(); dfn.process(fr, o) }   // warm-up
        val times = DoubleArray(hops)
        val c0 = Debug.threadCpuTimeNanos(); val w0 = System.nanoTime()
        for (h in 0 until hops) {
            for (i in 0 until 480) fr[i] = x48[h * 480 + i].toFloat()
            val a = System.nanoTime(); dfn.process(fr, o); times[h] = (System.nanoTime() - a) / 1e6
        }
        val dfnWall = (System.nanoTime() - w0) / 1e6; val dfnCpu = (Debug.threadCpuTimeNanos() - c0) / 1e6
        times.sort()
        say("DFN3: %.2f ms per 10 ms hop (median %.2f, p95 %.2f, max %.2f); CPU %.2f ms per hop -> real-time factor %.3f"
            .format(dfnWall / hops, times[hops / 2], times[hops * 95 / 100], times.last(), dfnCpu / hops, dfnWall / hops / 10))

        // 2) CAM++ embedding of a 1 s window (what the voice gate runs every 250 ms while someone talks)
        val x16 = FloatArray(16_000 * 3) { (x48[it * 3] * 0.8).toFloat() }
        repeat(3) { spk.embed(x16.copyOfRange(0, 16_000), 16_000) }
        val et = DoubleArray(20)
        val ec0 = Process.getElapsedCpuTime()                        // whole process: ORT's worker threads too
        for (k in et.indices) { val w = x16.copyOfRange(k * 1000, k * 1000 + 16_000); val a = System.nanoTime(); spk.embed(w, 16_000); et[k] = (System.nanoTime() - a) / 1e6 }
        val ecpu = (Process.getElapsedCpuTime() - ec0).toDouble() / et.size
        et.sort()
        say("CAM++: %.1f ms per 1 s window (median %.1f, max %.1f; CPU %.1f ms over its 2 threads); every 250 ms while someone talks -> %.2f ms wall, %.2f ms CPU per 10 ms on average"
            .format(et.average(), et[et.size / 2], et.last(), ecpu, et.average() / 25, ecpu / 25))

        // 3) the whole neural chain as Filtered hearing runs it: 64 kHz -> DFN -> processor at 32 kHz (all speech;
        //    a voice mode adds the CAM++ cost above while someone talks)
        val secs = 20.0
        val x64 = synth(secs, 64_000, 2)
        val gate = VoiceGate(spk, async = false)
        val front = NeuralFront(OrtModels.Dfn(dfnBytes), gate)
        val proc = HearingProcessor(32_000).apply { enabled = true; voiceMode = VoiceMode.ALL_SPEECH; setNoise(HearingProcessor.analyzeNoise(DoubleArray(96_000) { x64[it * 2] }, 32_000)) }
        val blk = 480
        val xo = DoubleArray(front.maxOut(blk)); val eo = DoubleArray(xo.size); val go = DoubleArray(xo.size); val yo = DoubleArray(xo.size)
        val b = DoubleArray(blk)
        val nc0 = Process.getElapsedCpuTime(); val nw0 = System.nanoTime()
        var off = 0
        while (off + blk <= x64.size) {
            System.arraycopy(x64, off, b, 0, blk)
            val m = front.process(b, blk, xo, eo, go)
            proc.process(xo, eo, go, m, yo)
            off += blk
        }
        val nWall = (System.nanoTime() - nw0) / 1e6; val nCpu = (Process.getElapsedCpuTime() - nc0).toDouble()
        say("Neural chain (64->48 kHz, DFN3, 48->32 kHz, remove all speech + learned sound): %.0f ms for %.0f s of audio -> %.2f ms per 10 ms, real-time factor %.3f; CPU %.0f ms per audio second (%.1f %% of one core)"
            .format(nWall, secs, nWall / secs / 100, nWall / secs / 1000, nCpu / secs, nCpu / secs / 10))

        // 4) Light: the classical chain alone
        val dec = Decimator2()
        val lp = HearingProcessor(32_000).apply { enabled = true; voiceMode = VoiceMode.ALL_SPEECH; clicksOn = true }
        val l32 = DoubleArray(blk); val ly = DoubleArray(blk)
        val lc0 = Process.getElapsedCpuTime(); val lw0 = System.nanoTime()
        off = 0
        while (off + blk <= x64.size) { System.arraycopy(x64, off, b, 0, blk); val m = dec.process(b, blk, l32); lp.process(l32, m, ly); off += blk }
        val lWall = (System.nanoTime() - lw0) / 1e6; val lCpu = (Process.getElapsedCpuTime() - lc0).toDouble()
        say("Light chain (all speech, clicks): %.2f ms per 10 ms, real-time factor %.3f; CPU %.0f ms per audio second (%.1f %% of one core)"
            .format(lWall / secs / 100, lWall / secs / 1000, lCpu / secs, lCpu / secs / 10))
        say("Added delay of the neural chain: %.1f ms (DFN 30 ms + resampling)".format(front.delay32 / 32.0))
        say("DONE")
        dfn.close(); spk.close(); gate.close()
    }

    companion object { private const val TAG = "FHBench" }
}
