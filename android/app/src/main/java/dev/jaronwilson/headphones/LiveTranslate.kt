package dev.jaronwilson.headphones

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live Translation's shared state between the service and the screen (same process), plus its settings
 * and the ML Kit language models. The transcript lives in memory only: nothing is written anywhere
 * unless the user taps Save on the transcript screen.
 */
object LiveTranslate {
    data class Entry(
        val time: Long, val direction: Direction, val heard: String, val translated: String,
        val from: String, val to: String,
    )

    /** What the screen shows. [running] = the service has the AirPods' mic; [status] = one line for the user. */
    @Volatile var running = false; private set
    @Volatile var starting = false; private set
    @Volatile var status = ""; private set
    @Volatile var caption = ""; private set           // partial result while someone is talking
    @Volatile var lastReply: Entry? = null; private set
    @Volatile var meHeld = false; private set
    val transcript = CopyOnWriteArrayList<Entry>()
    private const val MAX_ENTRIES = 500

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it() } }

    internal fun setRunning(c: Context, on: Boolean, starting: Boolean = false) {
        running = on; this.starting = starting
        if (!on) { caption = ""; meHeld = false }
        changed(); HeadphonesWidget.redraw(c)
    }
    internal fun setStatus(s: String) { status = s; changed() }
    internal fun setCaption(s: String) { caption = s; changed() }
    internal fun add(e: Entry) {
        transcript += e
        while (transcript.size > MAX_ENTRIES) transcript.removeAt(0)
        if (e.direction == Direction.MINE_TO_THEM) lastReply = e
        caption = ""; changed()
    }
    fun clear() { transcript.clear(); lastReply = null; caption = ""; changed() }

    /** The transcript as plain text, for Save. */
    fun transcriptText(): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return transcript.joinToString("\n\n") { e ->
            val who = if (e.direction == Direction.MINE_TO_THEM) "Me" else "Them"
            "${f.format(e.time)}  $who (${e.from}): ${e.heard}\n    -> (${e.to}): ${e.translated}"
        }
    }

    // ---- triggers ----
    /** Where a "who is talking" signal comes from. Only the on-screen button is wired up today; the AirPods
     *  stem press-and-hold or a head nod could call [trigger] later with their own source. */
    enum class TriggerSource { SCREEN, AIRPODS_STEM, HEAD_GESTURE }

    /** "I'm talking" pressed/released. While held, what is heard is my language, translated for them. */
    fun trigger(c: Context, source: TriggerSource, meTalking: Boolean) {
        if (!running) return
        meHeld = meTalking; changed()
        LiveTranslateService.meTalking(c, meTalking, source)
    }

    // ---- settings ----
    private fun p(c: Context) = c.getSharedPreferences("translate", Context.MODE_PRIVATE)
    fun them(c: Context): String = p(c).getString("them", TranslateLanguage.SPANISH) ?: TranslateLanguage.SPANISH
    fun me(c: Context): String = p(c).getString("me", TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
    fun setLangs(c: Context, them: String, me: String) = p(c).edit().putString("them", them).putString("me", me).apply()
    fun anc(c: Context): AncChoice = runCatching { AncChoice.valueOf(p(c).getString("anc", "ANC")!!) }.getOrDefault(AncChoice.ANC)
    fun setAnc(c: Context, a: AncChoice) = p(c).edit().putString("anc", a.name).apply()
    /** My replies spoken out loud on the phone speaker (off = shown on screen only). */
    fun speakReplies(c: Context) = p(c).getBoolean("speak_replies", true)
    fun setSpeakReplies(c: Context, on: Boolean) = p(c).edit().putBoolean("speak_replies", on).apply()

    // ---- languages and models (ML Kit, on-device) ----
    fun languages(): List<String> = TranslateLanguage.getAllLanguages().sortedBy { name(it) }
    fun name(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).ifEmpty { code }

    /** ML Kit doesn't publish exact sizes or download progress; its docs give about 30 MB per language. */
    const val MODEL_SIZE = "about 30 MB"

    private val downloading = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    fun isDownloading(code: String) = code in downloading

    fun downloadedModels(done: (Set<String>) -> Unit) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { set -> done(set.map { it.language }.toSet()) }
            .addOnFailureListener { done(emptySet()) }
    }

    /** Download over Wi-Fi only. [done] gets null on success or the reason. */
    fun download(code: String, done: (String?) -> Unit) {
        downloading += code; changed()
        val cond = DownloadConditions.Builder().requireWifi().build()
        RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder(code).build(), cond)
            .addOnSuccessListener { downloading -= code; done(null); changed() }
            .addOnFailureListener { downloading -= code; done(it.message ?: it.javaClass.simpleName); changed() }
    }

    fun delete(code: String, done: (String?) -> Unit) {
        RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(code).build())
            .addOnSuccessListener { done(null); changed() }
            .addOnFailureListener { done(it.message ?: it.javaClass.simpleName); changed() }
    }
}
