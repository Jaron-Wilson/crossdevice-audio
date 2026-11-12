package dev.jaronwilson.headphones

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.IBinder

/**
 * Applies the EQ on the phone with android.media.audiofx, without root.
 *
 * What Android allows a normal app (and what this does about it):
 *  1. Global output (audio session 0). Deprecated since Android 9, and whether it is honoured is up to
 *     the phone's audio HAL. We try DynamicsProcessing on session 0, then Equalizer on session 0; if
 *     the phone accepts one, every app's audio goes through it. Note: "accepted" is not "audible".
 *     Some builds create the effect and then ignore it. The status line says what happened.
 *  2. Per-app sessions. Music players that support external EQs (they broadcast
 *     ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION with their session id) get the EQ attached to their
 *     session. Apps that don't broadcast, which is most video apps and browsers, can't be reached.
 *
 * The effects live as long as this foreground service does: that's what keeps them from being garbage
 * collected (and released) when the app isn't on screen.
 */
class EqService : Service() {
    private sealed class Fx {
        abstract val effect: AudioEffect
        class Dp(override val effect: DynamicsProcessing) : Fx()
        class Eq(override val effect: Equalizer) : Fx()
    }

    private var global: Fx? = null
    private var globalError = ""
    private val sessions = HashMap<Int, Fx>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val sid = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
            if (sid <= 0) return
            when (i.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> if (sid !in sessions) {
                    make(sid)?.let { sessions[sid] = it; apply(it) }
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> sessions.remove(sid)?.let { release(it) }
            }
            report()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Equalizer", NotificationManager.IMPORTANCE_LOW))
        startFg()
        val f = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        // players send these to anyone listening, so the receiver must be exported
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_EXPORTED) else registerReceiver(receiver, f)
        global = runCatching { make(0, throwing = true) }.onFailure {
            globalError = (it.message ?: it.javaClass.simpleName)
        }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!EqModel.enabled(this)) { stopSelf(); return START_NOT_STICKY }
        global?.let { apply(it) }
        sessions.values.forEach { apply(it) }
        report()
        startFg()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        global?.let { release(it) }; global = null
        sessions.values.forEach { release(it) }; sessions.clear()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("status", "Off").apply()
        super.onDestroy()
    }

    private fun startFg() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("EQ: " + EqModel.presetLabel(this))
            .setContentText(statusText())
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    /** DynamicsProcessing if the phone has it for this session, else the classic Equalizer. */
    private fun make(session: Int, throwing: Boolean = false): Fx? {
        val errors = mutableListOf<String>()
        try {
            val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, true, DP_BANDS, false, 0, false, 0, true).build()
            return Fx.Dp(DynamicsProcessing(PRIORITY, session, cfg))
        } catch (e: Throwable) { errors += "DynamicsProcessing: ${e.message ?: e.javaClass.simpleName}" }
        try {
            return Fx.Eq(Equalizer(PRIORITY, session))
        } catch (e: Throwable) { errors += "Equalizer: ${e.message ?: e.javaClass.simpleName}" }
        if (throwing) throw RuntimeException(errors.joinToString("; "))
        return null
    }

    private fun apply(fx: Fx) {
        val bands = EqModel.bands(this); val g = EqModel.gains(this)
        runCatching {
            when (fx) {
                is Fx.Dp -> {
                    // DynamicsProcessing's pre-EQ is a bank of band gains split at cutoff frequencies;
                    // sample the biquad curve at each band's centre so it matches the other platforms.
                    val eq = DynamicsProcessing.Eq(true, true, DP_BANDS)
                    for (i in 0 until DP_BANDS) {
                        val centre = EqModel.freqAt((i + 0.5) / DP_BANDS)
                        val cutoff = EqModel.freqAt((i + 1.0) / DP_BANDS)
                        eq.setBand(i, DynamicsProcessing.EqBand(true, cutoff.toFloat(),
                            EqModel.responseDb(bands, g, centre).toFloat()))
                    }
                    fx.effect.setPreEqAllChannelsTo(eq)
                    fx.effect.setInputGainAllChannelsTo(EqModel.preamp(bands, g).toFloat())
                    // limiter stage: catches the peaks the half-cut preamp leaves (attack 1 ms, release 60 ms,
                    // ratio 20:1, threshold -1 dBFS) so boosts don't clip
                    fx.effect.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 20f, -1f, 0f))
                    fx.effect.enabled = true
                }
                is Fx.Eq -> {
                    val e = fx.effect
                    val (lo, hi) = e.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
                    for (b in 0 until e.numberOfBands) {
                        val hz = e.getCenterFreq(b.toShort()) / 1000.0
                        val mb = (EqModel.responseDb(bands, g, hz) * 100).toInt().coerceIn(lo, hi)
                        e.setBandLevel(b.toShort(), mb.toShort())
                    }
                    e.enabled = true
                }
            }
        }
    }

    private fun release(fx: Fx) = runCatching { fx.effect.enabled = false; fx.effect.release() }

    private fun statusText(): String = when {
        global != null -> "System-wide (output session 0, " + (if (global is Fx.Dp) "DynamicsProcessing" else "Equalizer") +
            "). If you hear no change, this phone ignores it." +
            (if (sessions.isNotEmpty()) " Plus ${sessions.size} app session(s)." else "")
        sessions.isNotEmpty() -> "Per-app: ${sessions.size} music app session(s). System-wide isn't allowed here."
        else -> "Waiting for a music app that supports external EQs. System-wide isn't allowed here" +
            (if (globalError.isNotEmpty()) " ($globalError)" else "") + "."
    }

    private fun report() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("status", statusText()).apply()
        startFg()
    }

    companion object {
        private const val CH = "eq"
        private const val PREFS = "eq_service"
        private const val PRIORITY = 1000
        private const val DP_BANDS = 24

        /** Start, update or stop the EQ to match [EqModel]. */
        fun update(c: Context) {
            val i = Intent(c, EqService::class.java)
            if (EqModel.enabled(c)) runCatching { c.startForegroundService(i) }
            else c.stopService(i)
        }

        fun status(c: Context): String =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("status", "Off") ?: "Off"
    }
}
