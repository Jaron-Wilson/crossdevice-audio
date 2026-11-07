package dev.jaronwilson.headphones

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * The AirPods connected to / disconnected from this phone. The phone and the laptop can't share the
 * AirPods (each one joining kicks the other), so tell the laptop panel to let go or take them back,
 * then redraw the widget.
 */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val dev: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (!dev.address.equals(Config.airpodsMac(context), ignoreCase = true)) return
        val onPhone = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
        Handoff.setLocal(context, onPhone)
        val pending = goAsync()
        thread {
            try {
                Handoff.tell(context, onPhone)
                HeadphonesWidget.refresh(context)
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
