package dev.jaronwilson.headphones

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Live Translation, all through the AirPods:
 *  - the AirPods become the communication device (Bluetooth SCO), so their mics feed the speech
 *    recognizer and voice audio plays in them; the audio mode is MODE_IN_COMMUNICATION only while this runs;
 *  - the on-device speech recognizer listens (in their language, allowed to switch to mine on Android 14+),
 *    restarted after every phrase;
 *  - ML Kit Language ID + [DirectionRouter] decide who spoke; ML Kit Translate (on-device) translates;
 *  - their words, in my language: text-to-speech as voice-communication audio -> the AirPods;
 *  - my words, in theirs: big on the screen and spoken on the phone's own speaker.
 * Recognition pauses while anything is spoken, so we don't translate ourselves.
 */
class LiveTranslateService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var route: AndroidAudioRoute
    private lateinit var session: TranslateSession
    private val echo = EchoGuard({ System.currentTimeMillis() })

    private var active = false
    private var stopping = false
    private var them = "es"
    private var me = "en"

    // recognizer
    private var rec: SpeechRecognizer? = null
    private var onDevice = false
    private var useDefault = false          // on-device failed for this language: the phone's default recognizer
    private var langSwitch = Build.VERSION.SDK_INT >= 34
    private var listening = false
    private var listenLang = "es"
    private var heardAs: String? = null
    private var errors = 0
    private var meHeld = false
    private var forceNext: Direction? = null

    // translation
    private val translators = HashMap<String, Translator>()
    private var langId: LanguageIdentifier? = null

    // speech out
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = 0
    private var utterance = 0
    private var player: MediaPlayer? = null
    /** null = not tried yet; true = setPreferredDevice(speaker) works during the session; false = needs a route switch. */
    private var directSpeakerWorks: Boolean? = null

    private val deviceCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val h = session.headset ?: return
            if (active && !route.stillThere(h.id)) end("The AirPods disconnected. Translation stopped.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        route = AndroidAudioRoute(this)
        session = TranslateSession(route, PhoneNoise(this)) { LiveTranslate.anc(this) }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Live Translation", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { if (active || LiveTranslate.starting) end(null) else stopSelf(); return START_NOT_STICKY }
            else -> if (!active && !LiveTranslate.starting) begin()
        }
        return START_NOT_STICKY
    }

    // ---------------- start / stop ----------------

    private fun begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            LiveTranslate.setStatus("Allow the microphone first (open Live Translation)."); stopSelf(); return
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NID, notification("Starting…"))
        } catch (e: Exception) {
            // e.g. started from the background: Android doesn't allow the mic then
            LiveTranslate.setStatus("Android wouldn't start the microphone service: ${e.message}"); stopSelf(); return
        }
        them = LiveTranslate.them(this); me = LiveTranslate.me(this)
        if (Langs.base(them) == Langs.base(me)) { fail("Pick two different languages."); return }
        LiveTranslate.setRunning(this, false, starting = true)
        LiveTranslate.setStatus("Checking language models…")
        LiveTranslate.downloadedModels { have ->
            val missing = listOf(them, me).filter { it !in have }
            if (missing.isNotEmpty()) {
                fail("Download the ${missing.joinToString(" and ") { LiveTranslate.name(it) }} model first (Wi-Fi).")
                return@downloadedModels
            }
            LiveTranslate.setStatus("Connecting to the AirPods' microphone…")
            io.execute {
                val r = session.start()
                main.post { started(r) }
            }
        }
    }

    private fun started(r: TranslateSession.Start) {
        when (r) {
            TranslateSession.Start.NoHeadset -> {
                fail("Connect your AirPods to this phone (with Calls allowed in their Bluetooth settings), then start again."); return
            }
            TranslateSession.Start.RouteFailed -> {
                fail("Android wouldn't route the AirPods' microphone (Bluetooth SCO). Try again, or reconnect the AirPods."); return
            }
            is TranslateSession.Start.Ok -> {
                if (LiveTranslate.starting.not()) { io.execute { session.stop() }; return }   // stopped while starting
                active = true
                getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCb, main)
                langId = LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.01f).build())
                tts = TextToSpeech(this) { s -> ttsReady = s == TextToSpeech.SUCCESS
                    if (!ttsReady) LiveTranslate.setStatus("Text-to-speech isn't available: translations are shown, not spoken.") }
                    .also { it.setOnUtteranceProgressListener(ttsListener) }
                LiveTranslate.setRunning(this, true)
                val note = r.ancNote.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
                LiveTranslate.setStatus("Listening through ${r.headset.name}.$note")
                updateNotification()
                newRecognizer(); listen()
            }
        }
    }

    private fun fail(msg: String) {
        LiveTranslate.setRunning(this, false)
        LiveTranslate.setStatus(msg)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    /** Stop everything and put the audio mode, route and AirPods noise mode back. */
    private fun end(msg: String?) {
        if (stopping) return
        stopping = true
        active = false
        LiveTranslate.setRunning(this, false)
        LiveTranslate.setStatus(msg ?: "Stopping…")
        runCatching { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCb) }
        runCatching { rec?.destroy() }; rec = null
        runCatching { tts?.stop(); tts?.shutdown() }; tts = null
        runCatching { player?.release() }; player = null
        translators.values.forEach { runCatching { it.close() } }; translators.clear()
        runCatching { langId?.close() }; langId = null
        io.execute {
            val note = session.stop()
            main.post {
                LiveTranslate.setStatus(listOfNotNull(msg, note.ifEmpty { null }).joinToString(" ").ifEmpty { "Stopped." })
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
    }

    override fun onDestroy() {
        if (active) {     // killed without a stop: still put the audio back
            active = false
            runCatching { rec?.destroy() }; runCatching { tts?.shutdown() }
            runCatching { session.stop() }
            LiveTranslate.setRunning(this, false)
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------------- recognition ----------------

    private fun newRecognizer() {
        runCatching { rec?.destroy() }
        onDevice = !useDefault && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        rec = (if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this))
            .also { it.setRecognitionListener(listener) }
    }

    private fun listen() {
        if (!active || speaking > 0 || listening) return
        val r = rec ?: return
        listenLang = if (meHeld) me else them
        val both = arrayListOf(Langs.localeTag(them), Langs.localeTag(me))
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Langs.localeTag(listenLang))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)   // the default recognizer: stay on the phone if it can
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (Build.VERSION.SDK_INT >= 34 && langSwitch && !meHeld) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, both)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, both)
            }
        }
        heardAs = null
        listening = true
        runCatching { r.startListening(i) }.onFailure { listening = false; main.postDelayed({ listen() }, 1000) }
    }

    private fun pauseListening() {
        if (listening) runCatching { rec?.cancel() }
        listening = false
        LiveTranslate.setCaption("")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(b: Bundle?) {
            if (speaking > 0) return
            first(b)?.let { LiveTranslate.setCaption(it) }
        }

        override fun onResults(b: Bundle?) {
            listening = false; errors = 0
            val text = first(b)
            if (speaking == 0 && !text.isNullOrBlank()) handle(text, heardAs ?: listenLang)
            else LiveTranslate.setCaption("")
            listen()
        }

        override fun onLanguageDetection(b: Bundle) {
            b.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let { heardAs = it }
        }

        override fun onError(error: Int) {
            listening = false
            if (!active || speaking > 0) return       // our own cancel() while speaking
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listen()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> end("The speech recognizer wasn't allowed the microphone.")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> when {
                    langSwitch -> { langSwitch = false; listen() }    // the second language's speech pack is missing
                    onDevice -> {
                        useDefault = true; newRecognizer()
                        LiveTranslate.setStatus("On-device speech recognition has no ${LiveTranslate.name(listenLang)} here; " +
                            "using the phone's default recognizer (asked to stay offline). Get the speech pack on the Live Translation screen.")
                        listen()
                    }
                    else -> end("Speech recognition doesn't support ${LiveTranslate.name(listenLang)} on this phone.")
                }
                else -> {
                    if (++errors > 8) { end("Speech recognition keeps failing (error $error). Translation stopped."); return }
                    newRecognizer(); main.postDelayed({ listen() }, 400L * errors)
                }
            }
        }
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.ifEmpty { null }

    // ---------------- who spoke, translate, speak ----------------

    private fun handle(text: String, heard: String) {
        val forced = forceNext ?: if (meHeld) Direction.MINE_TO_THEM else null
        forceNext = null
        if (forced == null && echo.isEcho(text)) { LiveTranslate.setCaption(""); return }
        val id = langId ?: return
        id.identifyPossibleLanguages(text).addOnCompleteListener { t ->
            if (!active) return@addOnCompleteListener
            val scores = if (t.isSuccessful) t.result.associate { it.languageTag to it.confidence } else emptyMap()
            when (DirectionRouter.decide(text, scores, me, them, heard, forced)) {
                Direction.THEIRS_TO_ME -> translate(text, them, me) { out ->
                    LiveTranslate.add(LiveTranslate.Entry(System.currentTimeMillis(), Direction.THEIRS_TO_ME, text, out, them, me))
                    speakToMe(out)
                }
                Direction.MINE_TO_THEM -> translate(text, me, them) { out ->
                    LiveTranslate.add(LiveTranslate.Entry(System.currentTimeMillis(), Direction.MINE_TO_THEM, text, out, me, them))
                    if (LiveTranslate.speakReplies(this)) speakToThem(out)
                }
                Direction.IGNORE -> LiveTranslate.setCaption("")
            }
        }
    }

    private fun translate(text: String, from: String, to: String, done: (String) -> Unit) {
        val t = translators.getOrPut("$from>$to") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        }
        t.translate(text)
            .addOnSuccessListener { if (active) done(it) }
            .addOnFailureListener { LiveTranslate.setStatus("Translation failed: ${it.message}") }
    }

    private fun voiceAttrs(usage: Int) = AudioAttributes.Builder().setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /** Their words in my language -> the AirPods (voice-communication audio follows the communication device). */
    private fun speakToMe(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        startSpeaking(text)
        t.setLanguage(Locale.forLanguageTag(Langs.localeTag(me)))
        t.setAudioAttributes(voiceAttrs(AudioAttributes.USAGE_VOICE_COMMUNICATION))
        t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "me-${++utterance}")
    }

    /** My words in their language -> a file, then played on the phone speaker (see [playOnSpeaker]). */
    private fun speakToThem(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        startSpeaking(text)
        val f = File(cacheDir, "reply-${++utterance}.wav")
        t.setLanguage(Locale.forLanguageTag(Langs.localeTag(them)))
        if (t.synthesizeToFile(text, Bundle(), f, "them-$utterance:${f.path}") != TextToSpeech.SUCCESS) doneSpeaking()
    }

    private fun startSpeaking(text: String) {
        speaking++
        echo.spoke(text)
        pauseListening()
    }

    private fun doneSpeaking() {
        speaking = (speaking - 1).coerceAtLeast(0)
        // a short tail so the end of our own audio isn't heard as speech
        if (speaking == 0) main.postDelayed({ listen() }, 250)
    }

    private val ttsListener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) {
            main.post {
                if (id != null && id.startsWith("them-")) playOnSpeaker(File(id.substringAfter(':')))
                else doneSpeaking()
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { main.post { doneSpeaking() } }
        override fun onError(id: String?, errorCode: Int) { main.post { doneSpeaking() } }
    }

    /**
     * The phone speaker while the AirPods are the call device. First try: media audio with
     * setPreferredDevice(built-in speaker), and check where Android actually routed it. If it went
     * anywhere else (some phones force everything to the call device), stop it and from then on switch
     * the communication device to the speaker for the reply, then back to the AirPods.
     */
    private fun playOnSpeaker(f: File) {
        if (!active) { f.delete(); doneSpeaking(); return }
        val spk = route.speaker()
        if (spk == null) { f.delete(); doneSpeaking(); return }
        if (directSpeakerWorks == false) { playWithRouteSwitch(f, spk); return }
        val mp = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(voiceAttrs(AudioAttributes.USAGE_MEDIA))
                setDataSource(f.path)
                setPreferredDevice(spk)
                prepare()
            }
        }.getOrElse { f.delete(); doneSpeaking(); return }
        player = mp
        var finished = false
        val finish = { if (!finished) { finished = true; runCatching { mp.release() }; if (player === mp) player = null; f.delete(); doneSpeaking() } }
        mp.setOnCompletionListener { finish() }
        mp.start()
        main.postDelayed({
            if (finished) return@postDelayed
            val dev = runCatching { mp.routedDevice }.getOrNull() ?: return@postDelayed   // unknown: let it play
            if (dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) directSpeakerWorks = true
            else {
                directSpeakerWorks = false
                finished = true
                runCatching { mp.stop(); mp.release() }; if (player === mp) player = null
                playWithRouteSwitch(f, spk)
            }
        }, 350)
    }

    private fun playWithRouteSwitch(f: File, spk: AudioDeviceInfo) {
        io.execute {
            val ok = active && route.routeToDevice(spk)
            main.post {
                if (!ok || !active) { f.delete(); backToAirPods(); return@post }
                val mp = runCatching {
                    MediaPlayer().apply {
                        setAudioAttributes(voiceAttrs(AudioAttributes.USAGE_VOICE_COMMUNICATION))
                        setDataSource(f.path); prepare()
                    }
                }.getOrElse { f.delete(); backToAirPods(); return@post }
                player = mp
                mp.setOnCompletionListener { runCatching { it.release() }; if (player === mp) player = null; f.delete(); backToAirPods() }
                mp.start()
            }
        }
    }

    private fun backToAirPods() {
        io.execute {
            val h = session.headset
            if (active && h != null && !route.routeTo(h)) main.post { end("Couldn't get the AirPods' microphone back after the reply.") }
            else main.post { doneSpeaking() }
        }
    }

    /** "Speak again" on the screen. */
    private fun repeatReply() {
        val e = LiveTranslate.lastReply ?: return
        if (active && speaking == 0) speakToThem(e.translated)
    }

    private fun setMeHeld(on: Boolean) {
        if (!active || on == meHeld) return
        meHeld = on
        if (on) {
            // restart in my language so the recognizer hears English as English
            pauseListening(); listen()
        } else if (listening) {
            // let the phrase in progress finish as mine, then go back to listening for them
            forceNext = Direction.MINE_TO_THEM
            runCatching { rec?.stopListening() }
        }
    }

    // ---------------- notification ----------------

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, TranslateActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("Live Translation")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NID,
            notification("${LiveTranslate.name(them)} ↔ ${LiveTranslate.name(me)}, through your AirPods"))
    }

    companion object {
        private const val CH = "translate"
        private const val NID = 7
        const val ACTION_STOP = "dev.jaronwilson.headphones.TRANSLATE_STOP"
        @Volatile private var instance: LiveTranslateService? = null

        /** Call from a visible activity: Android only lets a microphone service start from the foreground. */
        fun start(c: Context) = c.startForegroundService(Intent(c, LiveTranslateService::class.java))

        fun stop(c: Context) {
            if (instance != null) c.startService(Intent(c, LiveTranslateService::class.java).setAction(ACTION_STOP))
        }

        fun speakAgain() { instance?.let { s -> s.main.post { s.repeatReply() } } }

        internal fun meTalking(c: Context, on: Boolean, @Suppress("UNUSED_PARAMETER") source: LiveTranslate.TriggerSource) {
            instance?.let { s -> s.main.post { s.setMeHeld(on) } }
        }
    }
}
