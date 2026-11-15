package dev.jaronwilson.headphones

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot: Android 14+ does not let a microphone service start from BOOT_COMPLETED, so if Crusher
 * Adaptive was on, only post the "tap to resume" notification. Opening the app then starts it.
 */
class CrusherAdaptiveBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (CrusherAdaptiveCtl.enabled(context)) CrusherAdaptiveCtl.postResume(context)
    }
}
