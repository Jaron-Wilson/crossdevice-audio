package dev.jaronwilson.headphones

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * The AirPods or the Crusher connected to / disconnected from this phone. For the AirPods: the phone
 * and the laptop can't share them (each one joining kicks the other), so tell the laptop panel to let go
 * or take them back. For both: Last seen (time, battery, location if allowed), then redraw the widget.
 */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val dev: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        val which = Tracker.deviceOf(context, dev) ?: return
        val onPhone = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
        val isAirPods = which == Tracker.AIRPODS
        if (isAirPods) Handoff.setLocal(context, onPhone)
        // Last seen: note the connect, or save time/battery (and location, if allowed) on disconnect.
        if (onPhone) Tracker.onConnected(context, which)
        val pending = goAsync()
        val app = context.applicationContext
        thread {
            try {
                // in parallel, so the laptop hand-off isn't held up by a location fix
                val track = if (onPhone) null else thread { runCatching { Tracker.onDisconnected(app, which) } }
                if (isAirPods) Handoff.tell(app, onPhone)
                track?.join(15_000)
                HeadphonesWidget.refresh(app)
            } finally {
                pending.finish()
            }
        }
    }
}

object Handoff {
    private const val PREFS = "handoff"

    fun setLocal(context: Context, onPhone: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on_phone", onPhone).apply()

    fun local(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on_phone", false)

    /** Tell the laptop panel (if any) who has the AirPods; a few tries in case Tailscale is waking up. */
    fun tell(context: Context, onPhone: Boolean): Boolean {
        if (!Config.hasPanel(context)) return true
        repeat(4) { attempt ->
            if (runCatching { HeadphonesWidget.post(context, "/api/handoff", if (onPhone) "phone" else "laptop") }.isSuccess)
                return true
            Thread.sleep(2000L * (attempt + 1))
        }
        return false
    }
}
