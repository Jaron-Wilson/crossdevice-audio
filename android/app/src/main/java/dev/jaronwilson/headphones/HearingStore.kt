package dev.jaronwilson.headphones

import java.io.File

/**
 * What Filtered hearing has learned, kept in the app's private storage (filesDir/hearing.txt) so it survives a
 * restart: the learned sound (tones, steady spectrum, click shape), the classical voice model, and the neural
 * voice embeddings ("that" voice, "me"). Numbers only, no audio. "Forget" deletes the parts.
 * One "key=value" per line; arrays are comma-separated.
 */
class HearingStore(
    val noise: NoiseLearn? = null,
    val speaker: SpeakerModel? = null,
    val voices: Map<String, FloatArray> = emptyMap(),
) {
    fun encode(): String {
        val b = StringBuilder("v=1\n")
        fun arr(d: DoubleArray) = d.joinToString(",") { it.toString() }
        noise?.let { n ->
            b.append("noise.tones=").append(n.tonesHz.joinToString(",")).append('\n')
            b.append("noise.profile=").append(arr(n.profile)).append('\n')
            b.append("noise.level=").append(n.levelDb).append('\n')
            n.clickTemplate?.let { b.append("noise.clicks=").append(arr(it)).append('\n') }
            b.append("noise.clickRate=").append(n.clicksPerSec).append('\n')
        }
        speaker?.let { s ->
            b.append("speaker.mean=").append(arr(s.mean)).append('\n')
            b.append("speaker.std=").append(arr(s.std)).append('\n')
            b.append("speaker.threshold=").append(s.threshold).append('\n')
            b.append("speaker.sec=").append(s.voicedSec).append('\n')
        }
        for ((k, v) in voices) b.append("voice.").append(k).append('=').append(v.joinToString(",")).append('\n')
        return b.toString()
    }

    fun withNoise(n: NoiseLearn?) = HearingStore(n, speaker, voices)
    fun withSpeaker(s: SpeakerModel?) = HearingStore(noise, s, voices)
    fun withVoice(slot: String, v: FloatArray?) = HearingStore(noise, speaker, if (v == null) voices - slot else voices + (slot to v))

    companion object {
        fun decode(text: String): HearingStore {
            val m = text.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
            fun d(k: String) = m[k]?.takeIf { it.isNotEmpty() }?.split(',')?.map { it.toDouble() }?.toDoubleArray()
            val noise = d("noise.profile")?.let { prof ->
                NoiseLearn(d("noise.tones")?.toList() ?: emptyList(), prof, m["noise.level"]?.toDoubleOrNull() ?: -60.0,
                    d("noise.clicks"), m["noise.clickRate"]?.toDoubleOrNull() ?: 0.0)
            }
            val sp = d("speaker.mean")?.let { mean ->
                val std = d("speaker.std") ?: return@let null
                SpeakerModel(mean, std, m["speaker.threshold"]?.toDoubleOrNull() ?: return@let null, m["speaker.sec"]?.toDoubleOrNull() ?: 0.0)
            }
            val voices = m.filterKeys { it.startsWith("voice.") }.mapNotNull { (k, v) ->
                runCatching { k.removePrefix("voice.") to v.split(',').map { it.toFloat() }.toFloatArray() }.getOrNull()
            }.toMap()
            return HearingStore(noise, sp, voices)
        }

        fun load(f: File): HearingStore = runCatching { decode(f.readText()) }.getOrElse { HearingStore() }

        fun save(f: File, s: HearingStore) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(s.encode()); tmp.renameTo(f)
        }
    }
}
