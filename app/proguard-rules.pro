# ── JNI Bridge ────────────────────────────────────────────────────────────────
# These classes are called by name from C++ via JNI.
# If ProGuard renames them, the JNI calls will fail with UnsatisfiedLinkError.
-keep class com.infinity.ai.ai.runtime.LlamaJniBridge { *; }
-keep interface com.infinity.ai.ai.runtime.LlamaCallback { *; }
-keepclassmembers class com.infinity.ai.ai.runtime.LlamaJniBridge {
    native <methods>;
}

# ── Native → Kotlin callback (the dangerous direction) ────────────────────────
# C++ does GetObjectClass(callback) then GetMethodID(cls, "onToken", ...) on the
# CONCRETE instance — an anonymous `object : LlamaCallback` inside LlamaEngine.
# Nothing in Kotlin ever calls onToken/onComplete/onError, so R8 sees them as
# unreachable and is free to rename, inline or delete the method bodies. Keeping
# only the interface is not enough; the implementors must survive intact.
# Symptom if this is missing: generation silently produces zero tokens in release
# while debug works perfectly.
-keep class * implements com.infinity.ai.ai.runtime.LlamaCallback { *; }
-keepclassmembers class * implements com.infinity.ai.ai.runtime.LlamaCallback {
    void onToken(java.lang.String);
    void onComplete();
    void onError(java.lang.String);
}

# ── Enum constant names are persisted data, not just identifiers ──────────────
# EntryType goes through a Room TypeConverter using name()/valueOf(), so the
# constant name is written into the database. If R8 renames the constant, rows
# saved by an earlier build fail valueOf() with IllegalArgumentException on read.
# The health enums are already immune (they carry explicit `wireName` string
# literals rather than relying on name()), but this closes the whole class of bug.
-keepclassmembers enum * { *; }

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── DataStore ─────────────────────────────────────────────────────────────────
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# ── General Android ───────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
