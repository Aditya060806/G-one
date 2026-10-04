package com.gone.ai.ml

import java.io.File

/**
 * Reads the expected-output files the training scripts write next to each model
 * (`app/src/test/resources/ml/`). Plain text, so no JSON library is needed on the test
 * classpath, where Android's own is only a stub.
 */
object GoldenFile {

    data class FallCase(
        val source: String,
        val fall: Boolean,
        val impact: Int,
        val score: Float,
        val candidates: List<Int>,
        val x: FloatArray,
        val y: FloatArray,
        val z: FloatArray,
        val features: FloatArray
    )

    data class Fall(val threshold: Float, val cases: List<FallCase>)

    /** Unit tests may run from the module directory or the repository root. */
    fun text(name: String): String {
        val candidates = listOf(
            File("src/test/resources/ml/$name"),
            File("app/src/test/resources/ml/$name"),
            File("../app/src/test/resources/ml/$name")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("$name not found. Run the script in ml/ that writes it. Looked in: " +
                candidates.joinToString { it.absolutePath })
        return file.readText()
    }

    data class HrCase(
        val name: String,
        val resting: Float,
        val nowMillis: Long,
        val times: LongArray,
        val motion: FloatArray,
        val features: FloatArray,
        val outputs: FloatArray
    )

    /** `hr_context_golden.txt`, written by `python -m ml.hr_context.train`. Times in seconds become milliseconds. */
    fun hrContext(text: String = text("hr_context_golden.txt")): List<HrCase> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        val cases = mutableListOf<HrCase>()
        var i = 0
        fun millis(seconds: String) = Math.round(seconds.toDouble() * 1000)
        while (i < lines.size) {
            val head = lines[i].split(' ')
            check(head[0] == "case") { "unexpected line: ${lines[i]}" }
            val count = lines[i + 1].split(' ')[1].toInt()
            val times = LongArray(count)
            val motion = FloatArray(count)
            for (k in 0 until count) {
                val v = lines[i + 2 + k].split(' ')
                times[k] = millis(v[0])
                motion[k] = if (v[1] == "nan") Float.NaN else v[1].toFloat()
            }
            val features = lines[i + 2 + count].split(' ').drop(1).map { it.toFloat() }.toFloatArray()
            val outputs = lines[i + 3 + count].split(' ').drop(1).map { it.toFloat() }.toFloatArray()
            check(lines[i + 4 + count] == "end")
            cases += HrCase(head[1], head[3].toFloat(), millis(head[5]), times, motion, features, outputs)
            i += 5 + count
        }
        return cases
    }

    fun fall(text: String = text("fall_golden.txt")): Fall {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        var threshold = Float.NaN
        val cases = mutableListOf<FallCase>()
        var i = 0
        while (i < lines.size) {
            val words = lines[i].split(' ')
            when (words[0]) {
                "threshold" -> { threshold = words[1].toFloat(); i++ }
                "case" -> {
                    val source = words[1]
                    val fall = words[3] == "1"
                    val impact = words[5].toInt()
                    val score = words[7].toFloat()
                    val found = lines[i + 1].split(' ').drop(1).map { it.toInt() }
                    val count = lines[i + 2].split(' ')[1].toInt()
                    val x = FloatArray(count); val y = FloatArray(count); val z = FloatArray(count)
                    for (k in 0 until count) {
                        val v = lines[i + 3 + k].split(' ')
                        x[k] = v[0].toFloat(); y[k] = v[1].toFloat(); z[k] = v[2].toFloat()
                    }
                    val at = i + 3 + count
                    val rows = lines[at].split(' ')[1].toInt()
                    val features = FloatArray(rows * 4)
                    for (k in 0 until rows) {
                        val v = lines[at + 1 + k].split(' ')
                        for (c in 0 until 4) features[k * 4 + c] = v[c].toFloat()
                    }
                    check(lines[at + 1 + rows] == "end") { "malformed golden file near line ${at + 1 + rows}" }
                    cases += FallCase(source, fall, impact, score, found, x, y, z, features)
                    i = at + 2 + rows
                }
                else -> throw AssertionError("unexpected line in golden file: ${lines[i]}")
            }
        }
        return Fall(threshold, cases)
    }
}
