package dev.jaronwilson.headphones

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/*
 * The two models, run with ONNX Runtime on the CPU (on the phone: the onnxruntime-android AAR; in the unit
 * tests: the desktop onnxruntime jar, same API).
 *
 * - DeepFilterNet3 (Schröter et al.; MIT / Apache-2.0), exported as a single-frame streaming graph: 480 samples
 *   at 48 kHz in, 480 out, the whole recurrent state passed in and out as one flat tensor. Delay 1440 samples
 *   (30 ms: a 20 ms window and 2 frames of look-ahead). See android/tools/export_dfn3_stream.py.
 * - CAM++ (3D-Speaker, Alibaba; Apache-2.0), trained on VoxCeleb: 80 mel filterbank frames in, a 512-d
 *   speaker embedding out. Downloaded at build time (checksum checked), see app/build.gradle.kts.
 */
object OrtModels {
    const val DFN_ASSET = "models/dfn3_stream.onnx"
    const val SPK_ASSET = "models/campplus_en_voxceleb.onnx"
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    fun session(bytes: ByteArray, threads: Int): OrtSession {
        val o = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        }
        return env.createSession(bytes, o)
    }

    private fun direct(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** DeepFilterNet3, streaming. Not thread-safe: one audio thread. */
    class Dfn(bytes: ByteArray, threads: Int = 1) : Denoiser {
        private val s = session(bytes, threads)
        private val stateLen = (s.inputInfo["states"]!!.info as ai.onnxruntime.TensorInfo).shape[0].toInt()
        private val inBuf = direct(480)
        private val stBuf = direct(stateLen)
        private val shape1 = longArrayOf(480)
        private val shapeS = longArrayOf(stateLen.toLong())
        override val hop = 480
        override val delay = 1440

        override fun process(x: FloatArray, out: FloatArray, attenLimDb: Float): Float {
            inBuf.clear(); inBuf.put(x, 0, 480); inBuf.flip()
            stBuf.rewind()
            val tIn = OnnxTensor.createTensor(env, inBuf, shape1)
            val tSt = OnnxTensor.createTensor(env, stBuf, shapeS)
            val tLim = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(attenLimDb)), longArrayOf())
            try {
                s.run(mapOf("input_frame" to tIn, "states" to tSt, "atten_lim_db" to tLim)).use { r ->
                    (r.get(0) as OnnxTensor).floatBuffer.get(out, 0, 480)
                    val ns = (r.get(1) as OnnxTensor).floatBuffer
                    stBuf.clear(); stBuf.put(ns); stBuf.flip()
                    return (r.get(2) as OnnxTensor).floatBuffer.get(0)
                }
            } finally { tIn.close(); tSt.close(); tLim.close() }
        }

        override fun close() = s.close()
    }

    /** CAM++ speaker embeddings (16 kHz). Thread-safe enough for one worker thread. */
    class CamPlus(bytes: ByteArray, threads: Int = 2) : Embedder {
        private val s = session(bytes, threads)
        private val fb = KaldiFbank()

        override fun embed(x: FloatArray, n: Int): FloatArray {
            val f = fb.compute(x, n)
            val t = f.size
            if (t == 0) return FloatArray(512)
            val buf = FloatBuffer.allocate(t * 80)
            for (row in f) buf.put(row)
            buf.flip()
            OnnxTensor.createTensor(env, buf, longArrayOf(1, t.toLong(), 80)).use { tx ->
                s.run(mapOf("x" to tx)).use { r ->
                    val o = (r.get(0) as OnnxTensor).floatBuffer
                    val v = FloatArray(o.remaining()); o.get(v)
                    return VoiceGate.unit(v)
                }
            }
        }

        override fun close() = s.close()
    }
}
