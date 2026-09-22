package com.gone.ai.ai.runtime

/**
 * LlamaJniBridge
 *
 * Kotlin declarations for the native functions in infinity_jni.cpp (and the matching
 * infinity_jni_stub.cpp). The C++ symbol names must match
 * Java_com_gone_ai_ai_runtime_LlamaJniBridge_<methodName>, and the signatures of
 * both .cpp files must stay in step with this object.
 */
object LlamaJniBridge {

    init {
        // "infinity_jni" matches add_library(infinity_jni ...) in CMakeLists.txt.
        System.loadLibrary("infinity_jni")
    }

    /**
     * Load the GGUF model file into memory. Blocking; call off the main thread.
     * @param modelPath absolute path to the .gguf file on internal storage
     * @param nCtx      context window size in tokens
     * @param nThreads  number of CPU threads
     * @return true if model loaded successfully
     */
    external fun loadModel(modelPath: String, nCtx: Int, nThreads: Int): Boolean

    /**
     * Start a generation on a background native thread, streaming via [callback].
     *
     * Generations are serialized: a request made while another is running waits its
     * turn. [LlamaCallback.onComplete] or [LlamaCallback.onError] is called exactly once
     * for every request, including one that was cancelled.
     *
     * @return a request id for [stopGeneration], or 0 if the request could not start
     *   (in which case [LlamaCallback.onError] has already been called).
     */
    external fun generate(prompt: String, maxTokens: Int, callback: LlamaCallback): Long

    /** Cancel one request by id. Unknown or finished ids are ignored. Any thread. */
    external fun stopGeneration(requestId: Long)

    /** Cancel every running and queued request. Used before unloading the model. */
    external fun stopAllGenerations()

    /** Token count of [text] under the loaded vocabulary, or -1 if no model is loaded. */
    external fun countTokens(text: String): Int

    /** Cancel all requests, wait for the running one to finish, then free the model. */
    external fun unloadModel()

    /** True if a model is currently loaded and ready. */
    external fun isModelLoaded(): Boolean
}

/**
 * Callback that C++ invokes during generation, on the native generation thread.
 */
interface LlamaCallback {
    /** Called for each generated piece of text (always whole characters). */
    fun onToken(token: String)

    /** Called when generation ends normally, hits its token limit, or was cancelled. */
    fun onComplete()

    /** Called if the request failed. */
    fun onError(message: String)
}
