package com.gone.ai.ml

import android.content.Context
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * One small trained model: numbers in, numbers out.
 *
 * MODELS ARE SENSORS. A scorer never raises an alert. It produces a score; a named rule with a
 * fixed threshold decides what the score means, exactly as a rule decides what a heart rate
 * means. An interface so that everything built on a score runs in plain JVM tests with a fake.
 */
fun interface Scorer {
    fun score(input: FloatArray): FloatArray
}

/** What `assets/ml/manifest.json` records about one model. Written by the scripts in `ml/`. */
data class ModelInfo(
    val id: String,
    val version: Int,
    val file: String,
    val sha256: String,
    val bytes: Int,
    val parameters: Int,
    val quantisation: String,
    /** The score at or above which the model's rule acts; null for models that output a value. */
    val threshold: Float?,
    val trainedOn: String,
    val tested: String,
    /** Test results by name, as measured on people held out of training. */
    val metrics: Map<String, Double>,
    /** Measured on Qualcomm's hosted Snapdragon phones; empty until `ml/aihub/profile.py` has run. */
    val snapdragon: List<SnapdragonRun>,
    /** Model-specific settings (window sizes and the like), as written. */
    val input: JSONObject
)

data class SnapdragonRun(val device: String, val chipset: String, val inferenceMicros: Long, val runsOn: String)

/** The models shipped in the APK. */
object ModelRegistry {

    private const val DIR = "ml"
    @Volatile private var cached: Map<String, ModelInfo>? = null

    fun all(context: Context): Map<String, ModelInfo> = cached ?: synchronized(this) {
        cached ?: read(context).also { cached = it }
    }

    fun info(context: Context, id: String): ModelInfo? = all(context)[id]

    /**
     * Loads a model, or returns null when it is missing or is not the file the manifest
     * describes. A model that fails this check is not run: its rule then stays silent, which is
     * the safe direction.
     */
    fun open(context: Context, id: String): TfliteScorer? {
        val info = info(context, id) ?: return null
        return runCatching {
            val bytes = context.assets.open("$DIR/${info.file}").use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (digest != info.sha256) return null
            TfliteScorer(ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() })
        }.getOrNull()
    }

    private fun read(context: Context): Map<String, ModelInfo> = runCatching {
        val text = context.assets.open("$DIR/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val models = JSONObject(text).getJSONObject("models")
        models.keys().asSequence().associateWith { id ->
            val m = models.getJSONObject(id)
            val metrics = m.optJSONObject("metrics") ?: JSONObject()
            val runs = m.optJSONArray("snapdragon")
            ModelInfo(
                id = id,
                version = m.getInt("version"),
                file = m.getString("file"),
                sha256 = m.getString("sha256"),
                bytes = m.getInt("bytes"),
                parameters = m.getInt("parameters"),
                quantisation = m.optString("quantisation"),
                threshold = if (m.has("threshold")) m.getDouble("threshold").toFloat() else null,
                trainedOn = m.optString("trained_on"),
                tested = m.optString("tested"),
                metrics = metrics.keys().asSequence()
                    .filter { !metrics.isNull(it) }
                    .associateWith { metrics.getDouble(it) },
                snapdragon = (0 until (runs?.length() ?: 0)).map { i ->
                    val r = runs!!.getJSONObject(i)
                    SnapdragonRun(r.getString("device"), r.optString("chipset"), r.getLong("inference_micros"), r.optString("runs_on"))
                },
                input = m.optJSONObject("input") ?: JSONObject()
            )
        }
    }.getOrDefault(emptyMap())
}

/**
 * A model run by LiteRT on the phone's CPU.
 *
 * The models here are a few kilobytes and answer in well under a millisecond, so two threads on
 * the CPU are faster than waking an accelerator. The same files are compiled for and timed on
 * Snapdragon NPUs by `ml/aihub/profile.py`; those figures are in the manifest.
 */
class TfliteScorer(model: ByteBuffer) : Scorer, AutoCloseable {

    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    /** Values the model reads per run. */
    val inputSize = interpreter.getInputTensor(0).shape().fold(1) { a, b -> a * b }
    private val outputSize = interpreter.getOutputTensor(0).shape().fold(1) { a, b -> a * b }
    private val input = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())

    @Synchronized
    override fun score(input: FloatArray): FloatArray {
        require(input.size == inputSize) { "this model reads $inputSize values, was given ${input.size}" }
        this.input.rewind()
        this.input.asFloatBuffer().put(input)
        output.rewind()
        interpreter.run(this.input, output)
        output.rewind()
        return FloatArray(outputSize).also { output.asFloatBuffer().get(it) }
    }

    @Synchronized
    override fun close() = interpreter.close()

    companion object {
        /** A model file outside the APK's assets, for tests. */
        fun fromFile(path: String): TfliteScorer = FileInputStream(path).use { stream ->
            TfliteScorer(stream.channel.map(FileChannel.MapMode.READ_ONLY, 0, stream.channel.size()))
        }
    }
}
