#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include <pthread.h>

#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

#define LOG_TAG "InfinityLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Global state ──────────────────────────────────────────────────────────────
// g_state_mutex    : guards g_model / g_ctx pointer reads+writes
// g_gen_mutex      : ensures only one generation thread runs at a time
// g_requests       : per-request cancellation flags, keyed by request id
//
// Cancellation is PER REQUEST, not global. The engine is shared by chat, the document
// tools and the health-alert explainer. With a single global stop flag, a chat screen
// starting a new reply cancelled whatever else happened to be running — including an
// alert explanation — and the explainer's own timeout killed the user's chat reply.
static pthread_mutex_t   g_state_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t   g_gen_mutex   = PTHREAD_MUTEX_INITIALIZER;
static llama_model*      g_model       = nullptr;
static llama_context*    g_ctx         = nullptr;
static bool              g_backend     = false;
static JavaVM*           g_jvm         = nullptr;

// Tokens currently held in g_ctx's KV cache for sequence 0, in order. Guarded by
// g_gen_mutex: only the generation thread and load/unload (which take that mutex) touch it.
//
// Consecutive chat requests share most of their prompt — the system prompt, any attached
// document and every earlier turn — so a new request keeps the longest common prefix and
// decodes only what differs. Prefill is the slowest part of a reply on a phone CPU, and
// without this every turn re-read the whole conversation.
static std::vector<llama_token> g_cached_tokens;

using CancelFlag = std::shared_ptr<std::atomic<bool>>;
static std::mutex                               g_requests_mutex;
static std::unordered_map<int64_t, CancelFlag>  g_requests;
static std::atomic<int64_t>                     g_next_request_id{1};

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// ── String conversion ─────────────────────────────────────────────────────────
// JNI's *StringUTF* functions use "modified UTF-8", which is not UTF-8: characters
// outside the BMP (emoji, some scripts) are encoded as surrogate pairs, and passing
// real 4-byte UTF-8 to NewStringUTF aborts under CheckJNI (on by default in debuggable
// builds). llama.cpp needs standard UTF-8 in and produces standard UTF-8 out, so both
// directions go through UTF-16 explicitly.

static void append_utf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

static std::string jstring_to_utf8(JNIEnv* env, jstring s) {
    if (!s) return "";
    const jsize len = env->GetStringLength(s);
    const jchar* chars = env->GetStringChars(s, nullptr);
    if (!chars) return "";
    std::string out;
    out.reserve(static_cast<size_t>(len) * 3);
    for (jsize i = 0; i < len; i++) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len &&
            chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            i++;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;   // unpaired surrogate
        }
        append_utf8(out, cp);
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

/**
 * Number of leading bytes that form complete UTF-8 sequences.
 *
 * A token piece can end in the middle of a multi-byte character — Qwen routinely
 * splits emoji and Devanagari across tokens — so a trailing partial sequence is held
 * back until the next piece completes it.
 */
static size_t utf8_complete_prefix(const std::string& s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 3 && back <= n; back++) {
        const auto c = static_cast<unsigned char>(s[n - back]);
        if ((c >> 6) == 0x2) continue;   // continuation byte, keep looking for the lead
        size_t need = 1;
        if ((c >> 5) == 0x6)       need = 2;
        else if ((c >> 4) == 0xE)  need = 3;
        else if ((c >> 3) == 0x1E) need = 4;
        return back < need ? n - back : n;
    }
    return n;   // malformed run of continuation bytes: let the decoder replace it
}

static std::u16string utf8_to_utf16(const char* s, size_t n) {
    std::u16string out;
    out.reserve(n);
    size_t i = 0;
    while (i < n) {
        const auto c = static_cast<unsigned char>(s[i]);
        uint32_t cp;
        size_t len;
        if (c < 0x80)              { cp = c;        len = 1; }
        else if ((c >> 5) == 0x6)  { cp = c & 0x1F; len = 2; }
        else if ((c >> 4) == 0xE)  { cp = c & 0x0F; len = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; len = 4; }
        else { out.push_back(0xFFFD); i++; continue; }

        if (i + len > n) { out.push_back(0xFFFD); break; }

        bool ok = true;
        for (size_t k = 1; k < len; k++) {
            const auto cc = static_cast<unsigned char>(s[i + k]);
            if ((cc >> 6) != 0x2) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok) { out.push_back(0xFFFD); i++; continue; }

        const bool overlong = (len == 2 && cp < 0x80) || (len == 3 && cp < 0x800) ||
                              (len == 4 && cp < 0x10000);
        if (overlong || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            out.push_back(0xFFFD);
        } else if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
        i += len;
    }
    return out;
}

// ── Request registry ──────────────────────────────────────────────────────────

static void unregister_request(int64_t id) {
    std::lock_guard<std::mutex> lock(g_requests_mutex);
    g_requests.erase(id);
}

static void cancel_all_requests() {
    std::lock_guard<std::mutex> lock(g_requests_mutex);
    for (auto& entry : g_requests) entry.second->store(true);
}

struct GenContext {
    int64_t     id;
    CancelFlag  cancelled;
    std::string prompt;
    int         maxTokens;
    jobject     callback;
    jmethodID   onToken;
    jmethodID   onComplete;
    jmethodID   onError;
};

static void run_generation(JNIEnv* env, GenContext* ctx) {
    auto fireError = [&](const char* msg) {
        LOGE("request %lld: %s", static_cast<long long>(ctx->id), msg);
        jstring jmsg = env->NewStringUTF(msg);   // ASCII only, safe for modified UTF-8
        env->CallVoidMethod(ctx->callback, ctx->onError, jmsg);
        env->DeleteLocalRef(jmsg);
        if (env->ExceptionCheck()) env->ExceptionClear();
    };

    // Every exit path notifies Kotlin exactly once. The Kotlin side relies on this to
    // close the request's flow; a silent return used to leave a collector hanging
    // until some unrelated timeout fired.
    auto fireComplete = [&]() {
        env->CallVoidMethod(ctx->callback, ctx->onComplete);
        if (env->ExceptionCheck()) env->ExceptionClear();
    };

    auto emit = [&](const char* bytes, size_t n) -> bool {
        const std::u16string u16 = utf8_to_utf16(bytes, n);
        if (u16.empty()) return true;
        jstring jtok = env->NewString(reinterpret_cast<const jchar*>(u16.data()),
                                      static_cast<jsize>(u16.size()));
        env->CallVoidMethod(ctx->callback, ctx->onToken, jtok);
        env->DeleteLocalRef(jtok);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return true;
    };

    // Cancelled while queued behind another generation: nothing to do.
    if (ctx->cancelled->load()) { fireComplete(); return; }

    // unloadModel() takes g_gen_mutex before freeing, and this thread holds it for the
    // whole generation, so pointers snapshotted here stay valid until we return.
    pthread_mutex_lock(&g_state_mutex);
    llama_model*   model = g_model;
    llama_context* lctx  = g_ctx;
    pthread_mutex_unlock(&g_state_mutex);

    if (!model || !lctx) {
        fireError("Model not loaded");
        return;
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    // ── Tokenize ──────────────────────────────────────────────────────────────
    const int32_t probe = llama_tokenize(vocab, ctx->prompt.c_str(),
                                         static_cast<int32_t>(ctx->prompt.size()),
                                         nullptr, 0, true, true);
    const int n = probe < 0 ? -probe : probe;
    if (n <= 0) { fireError("Tokenization failed"); return; }

    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, ctx->prompt.c_str(), static_cast<int32_t>(ctx->prompt.size()),
                       tokens.data(), n, true, true) < 0) {
        fireError("Tokenization failed");
        return;
    }

    // ── Context budget ────────────────────────────────────────────────────────
    // The Kotlin side trims history to fit, but a single oversized message can still
    // exceed the window. Reject it with a clear message instead of a decode failure,
    // and never ask for more output than the remaining context can hold.
    const int n_ctx = static_cast<int>(llama_n_ctx(lctx));
    if (n >= n_ctx - 8) {
        fireError("Prompt is too long for the model's context window");
        return;
    }
    const int max_new = std::min(ctx->maxTokens, n_ctx - n - 1);
    LOGI("request %lld: %d prompt tokens, up to %d new",
         static_cast<long long>(ctx->id), n, max_new);

    // ── Reuse the cached prefix ───────────────────────────────────────────────
    // At least one prompt token is always decoded, because sampling needs fresh logits.
    llama_memory_t mem = llama_get_memory(lctx);
    size_t keep = 0;
    const size_t limit = std::min(g_cached_tokens.size(), static_cast<size_t>(n - 1));
    while (keep < limit && g_cached_tokens[keep] == tokens[keep]) keep++;
    if (keep == 0 || !llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    g_cached_tokens.assign(tokens.begin(), tokens.begin() + static_cast<long>(keep));
    LOGI("request %lld: reusing %zu of %d prompt tokens", static_cast<long long>(ctx->id), keep, n);

    // ── Prompt prefill, in chunks so cancellation is checked between them ─────
    const int64_t prefill_start = ggml_time_us();
    for (int i = static_cast<int>(keep); i < n; i += 512) {
        if (ctx->cancelled->load()) { fireComplete(); return; }
        const int chunk = std::min(512, n - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
        if (llama_decode(lctx, batch) != 0) {
            // The cache now holds an unknown part of this chunk: start clean next time.
            llama_memory_clear(mem, true);
            g_cached_tokens.clear();
            fireError("Prompt decode failed");
            return;
        }
        g_cached_tokens.insert(g_cached_tokens.end(), tokens.begin() + i, tokens.begin() + i + chunk);
    }
    const int64_t prefill_us = ggml_time_us() - prefill_start;
    const int prefilled = n - static_cast<int>(keep);
    if (prefilled > 0 && prefill_us > 0) {
        LOGI("request %lld: prefilled %d tokens in %lld ms (%.1f tokens/s)",
             static_cast<long long>(ctx->id), prefilled, static_cast<long long>(prefill_us / 1000),
             prefilled * 1e6 / static_cast<double>(prefill_us));
    }

    // ── Sampler chain ─────────────────────────────────────────────────────────
    // Order matters: penalties reshape the logits first, top-k/top-p then prune the
    // candidate set, and temperature is applied last to what survives. A 1.5B model
    // without a repetition penalty loops readily on long answers.
    auto sparams        = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    if (!smpl) { fireError("Failed to initialize sampler"); return; }

    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ── Token generation loop ─────────────────────────────────────────────────
    std::string pending;              // bytes of a not-yet-complete UTF-8 character
    std::vector<char> piece(256);

    for (int i = 0; i < max_new; i++) {
        if (ctx->cancelled->load()) break;

        const llama_token tok = llama_sampler_sample(smpl, lctx, -1);  // also accepts tok
        if (llama_vocab_is_eog(vocab, tok)) break;

        int len = llama_token_to_piece(vocab, tok, piece.data(),
                                       static_cast<int32_t>(piece.size()), 0, true);
        if (len < 0) {
            piece.resize(static_cast<size_t>(-len));
            len = llama_token_to_piece(vocab, tok, piece.data(),
                                       static_cast<int32_t>(piece.size()), 0, true);
            if (len < 0) break;
        }
        pending.append(piece.data(), static_cast<size_t>(len));

        const size_t ready = utf8_complete_prefix(pending);
        if (ready > 0) {
            if (!emit(pending.data(), ready)) break;
            pending.erase(0, ready);
        }

        llama_batch next = llama_batch_get_one(const_cast<llama_token*>(&tok), 1);
        if (llama_decode(lctx, next) != 0) break;   // context full: end the answer here
        g_cached_tokens.push_back(tok);
    }

    if (!pending.empty() && !ctx->cancelled->load()) {
        emit(pending.data(), pending.size());       // malformed tail becomes U+FFFD
    }

    llama_sampler_free(smpl);
    fireComplete();
}

static void* generation_thread(void* arg) {
    auto* ctx = static_cast<GenContext*>(arg);

    // One generation at a time: all requests share one llama_context.
    pthread_mutex_lock(&g_gen_mutex);

    JNIEnv* env = nullptr;
    JavaVMAttachArgs attachArgs = { JNI_VERSION_1_6, "llama-inference", nullptr };
    if (g_jvm->AttachCurrentThread(&env, &attachArgs) != JNI_OK || !env) {
        LOGE("Failed to attach inference thread to JVM");
        pthread_mutex_unlock(&g_gen_mutex);
        unregister_request(ctx->id);
        // env is null here — cannot call DeleteGlobalRef, so the global ref leaks.
        // This is an unrecoverable JVM error; leaking one ref is acceptable.
        delete ctx;
        return nullptr;
    }

    run_generation(env, ctx);

    unregister_request(ctx->id);
    env->DeleteGlobalRef(ctx->callback);
    delete ctx;
    g_jvm->DetachCurrentThread();
    pthread_mutex_unlock(&g_gen_mutex);
    return nullptr;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_loadModel(
        JNIEnv* env, jobject, jstring modelPath, jint nCtx, jint nThreads) {

    // Replacing a loaded model must not free it under a running generation.
    pthread_mutex_lock(&g_state_mutex);
    const bool hadModel = g_model || g_ctx;
    pthread_mutex_unlock(&g_state_mutex);
    if (hadModel) {
        cancel_all_requests();
        pthread_mutex_lock(&g_gen_mutex);
        pthread_mutex_lock(&g_state_mutex);
        if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
        if (g_model) { llama_model_free(g_model); g_model = nullptr; }
        g_cached_tokens.clear();
        pthread_mutex_unlock(&g_state_mutex);
        pthread_mutex_unlock(&g_gen_mutex);
    }

    if (!g_backend) {
        llama_backend_init();
        ggml_backend_register(ggml_backend_cpu_reg());
        g_backend = true;
        LOGI("llama backend + CPU registered");
    }

    llama_log_set([](ggml_log_level level, const char* text, void*) {
        if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN)
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[llama] %s", text);
        else
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "[llama] %s", text);
    }, nullptr);

    const std::string path = jstring_to_utf8(env, modelPath);
    LOGI("Loading model: %s", path.c_str());

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;

    llama_model* new_model = llama_model_load_from_file(path.c_str(), mp);
    if (!new_model) { LOGE("Failed to load model"); return JNI_FALSE; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(nCtx);
    cp.n_batch         = 512;
    cp.n_ubatch        = 512;
    cp.n_threads       = static_cast<int32_t>(nThreads);
    cp.n_threads_batch = static_cast<int32_t>(nThreads);

    llama_context* new_ctx = llama_init_from_model(new_model, cp);
    if (!new_ctx) {
        LOGE("Failed to create context");
        llama_model_free(new_model);
        return JNI_FALSE;
    }

    // Assign both under one lock so isModelLoaded() never sees half an engine.
    pthread_mutex_lock(&g_gen_mutex);
    pthread_mutex_lock(&g_state_mutex);
    g_model = new_model;
    g_ctx   = new_ctx;
    g_cached_tokens.clear();
    pthread_mutex_unlock(&g_state_mutex);
    pthread_mutex_unlock(&g_gen_mutex);

    LOGI("Model loaded OK. ctx=%d tokens", nCtx);
    return JNI_TRUE;
}

/** @return request id (> 0) for [stopGeneration], or 0 if the thread could not start. */
JNIEXPORT jlong JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_generate(
        JNIEnv* env, jobject, jstring prompt, jint maxTokens, jobject callback) {

    jclass    cls        = env->GetObjectClass(callback);
    jmethodID onToken    = env->GetMethodID(cls, "onToken",    "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(cls, "onComplete", "()V");
    jmethodID onError    = env->GetMethodID(cls, "onError",    "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);

    auto* ctx       = new GenContext();
    ctx->id         = g_next_request_id.fetch_add(1);
    ctx->cancelled  = std::make_shared<std::atomic<bool>>(false);
    ctx->prompt     = jstring_to_utf8(env, prompt);
    ctx->maxTokens  = static_cast<int>(maxTokens);
    ctx->callback   = env->NewGlobalRef(callback);
    ctx->onToken    = onToken;
    ctx->onComplete = onComplete;
    ctx->onError    = onError;

    // Registered before the thread starts, so a stop issued immediately is not lost.
    {
        std::lock_guard<std::mutex> lock(g_requests_mutex);
        g_requests[ctx->id] = ctx->cancelled;
    }
    const int64_t id = ctx->id;

    pthread_t      thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    const int rc = pthread_create(&thread, &attr, generation_thread, ctx);
    pthread_attr_destroy(&attr);

    if (rc != 0) {
        LOGE("Failed to create inference thread");
        unregister_request(id);
        jstring jmsg = env->NewStringUTF("Failed to start inference thread");
        env->CallVoidMethod(callback, onError, jmsg);
        env->DeleteLocalRef(jmsg);
        env->DeleteGlobalRef(ctx->callback);
        delete ctx;
        return 0;
    }
    return static_cast<jlong>(id);
}

/** Cancel one request. Unknown or finished ids are ignored. */
JNIEXPORT void JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_stopGeneration(JNIEnv*, jobject, jlong requestId) {
    std::lock_guard<std::mutex> lock(g_requests_mutex);
    auto it = g_requests.find(static_cast<int64_t>(requestId));
    if (it != g_requests.end()) it->second->store(true);
}

JNIEXPORT void JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_stopAllGenerations(JNIEnv*, jobject) {
    cancel_all_requests();
}

/** Token count for [text] with the loaded vocabulary, or -1 if no model is loaded. */
JNIEXPORT jint JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_countTokens(JNIEnv* env, jobject, jstring text) {
    const std::string s = jstring_to_utf8(env, text);
    jint result = -1;
    // Held for the whole tokenize so unloadModel() cannot free the vocab underneath.
    pthread_mutex_lock(&g_state_mutex);
    if (g_model) {
        const llama_vocab* vocab = llama_model_get_vocab(g_model);
        const int32_t r = llama_tokenize(vocab, s.c_str(), static_cast<int32_t>(s.size()),
                                         nullptr, 0, true, true);
        result = r < 0 ? -r : r;
    }
    pthread_mutex_unlock(&g_state_mutex);
    return result;
}

JNIEXPORT void JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_unloadModel(JNIEnv*, jobject) {
    // Ask every running or queued generation to finish, then wait for the running one
    // to release g_gen_mutex before freeing anything.
    cancel_all_requests();
    pthread_mutex_lock(&g_gen_mutex);
    pthread_mutex_lock(&g_state_mutex);
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_cached_tokens.clear();
    pthread_mutex_unlock(&g_state_mutex);
    pthread_mutex_unlock(&g_gen_mutex);
    LOGI("Model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_isModelLoaded(JNIEnv*, jobject) {
    pthread_mutex_lock(&g_state_mutex);
    const jboolean result = (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&g_state_mutex);
    return result;
}

} // extern "C"
