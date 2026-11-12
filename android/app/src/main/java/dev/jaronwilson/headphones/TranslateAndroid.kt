package dev.jaronwilson.headphones

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** [AudioRoute] on Android 12+: the AirPods' Bluetooth SCO link as the communication device. */
class AndroidAudioRoute(private val c: Context) : AudioRoute {
    private val am = c.getSystemService(AudioManager::class.java)
    private val exec = Executors.newSingleThreadExecutor()
    private var focusReq: AudioFocusRequest? = null

    private fun headsets() = am.availableCommunicationDevices.filter {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    /** The AirPods picked in the app if they can carry a call; otherwise any Bluetooth headset that can. */
    override fun findHeadset(): AudioRoute.Headset? {
        val mac = Config.airpodsMac(c)
        val all = headsets()
        val mine = all.firstOrNull { mac.isNotEmpty() && it.address.equals(mac, ignoreCase = true) }
        val d = mine ?: all.firstOrNull() ?: return null
        return AudioRoute.Headset(d.id, d.productName?.toString()?.ifEmpty { null } ?: "Bluetooth headset", mine != null)
    }

    /** Still connected (the session stops if the AirPods go away). */
    fun stillThere(id: Int) = am.availableCommunicationDevices.any { it.id == id }

    override fun currentMode() = am.mode
    override fun setMode(mode: Int) { am.mode = mode }

    override fun routeTo(h: AudioRoute.Headset): Boolean =
        am.availableCommunicationDevices.firstOrNull { it.id == h.id }?.let { routeToDevice(it) } ?: false

    /** setCommunicationDevice, then wait (up to 6 s) until Android reports it in use: the SCO link takes a moment. */
    fun routeToDevice(d: AudioDeviceInfo): Boolean {
        if (am.communicationDevice?.id == d.id) return true
        val latch = CountDownLatch(1)
        val l = AudioManager.OnCommunicationDeviceChangedListener { dev -> if (dev?.id == d.id) latch.countDown() }
        am.addOnCommunicationDeviceChangedListener(exec, l)
        try {
            if (!am.setCommunicationDevice(d)) return false
            latch.await(6, TimeUnit.SECONDS)
        } finally {
            am.removeOnCommunicationDeviceChangedListener(l)
        }
        return am.communicationDevice?.id == d.id
    }

    fun speaker(): AudioDeviceInfo? =
        am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    override fun clearRoute() = am.clearCommunicationDevice()

    /** Transient focus as a voice call: music apps pause for the session and resume after. */
    override fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .build()
        focusReq = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandonFocus() { focusReq?.let { am.abandonAudioFocusRequest(it) }; focusReq = null }
}

/** [NoiseControl] through [PhoneDirect] (AAP straight from the phone). */
class PhoneNoise(private val c: Context) : NoiseControl {
    override fun available() = Config.hasAirPods(c) && PhoneDirect.status(c) != "fail"

    override fun read(): AncState {
        val mode = PhoneDirect.mode(c, 0)
        val s = PhoneDirect.settings(c, Long.MAX_VALUE)
        return AncState(mode, if (s.isNull("adaptive_level")) null else s.optInt("adaptive_level"))
    }

    override fun apply(choice: AncChoice) {
        when (choice) {
            AncChoice.ANC -> PhoneDirect.setMode(c, "anc")
            AncChoice.BLOCK_MAX -> PhoneDirect.setAdaptive(c, 100)
            AncChoice.KEEP -> {}
        }
    }

    override fun restore(prev: AncState, applied: AncChoice) {
        val mode = prev.mode ?: return
        if (applied == AncChoice.BLOCK_MAX && prev.level != null && prev.level != 100)
            PhoneDirect.setSettings(c, listOf("adaptive_level" to prev.level), mode = mode)
        else PhoneDirect.setMode(c, mode)
    }
}
