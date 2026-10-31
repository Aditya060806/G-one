package com.gone.ai.health.explain

import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.voice.AppLanguage

/**
 * The sentence the phone says aloud when an alert is raised.
 *
 * PURE KOTLIN. Written out for every alert type and language rather than produced by the
 * model: a spoken alert is heard once and cannot be scrolled back, so its words must be known
 * in advance and reviewable here. Both `when`s are exhaustive, so a new alert type or language
 * does not compile until it has its wording.
 *
 * Short on purpose. It names the finding and says what to do; the full explanation stays on
 * screen and in the notification.
 */
object SpokenAlerts {

    fun sentence(type: AnomalyType, severity: Severity, language: AppLanguage): String =
        "${lead(severity, language)} ${label(type, language)}. ${guidance(severity, language)}"

    private fun lead(severity: Severity, language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> when (severity) {
            Severity.CRITICAL -> "Urgent alert:"
            Severity.MODERATE -> "Health alert:"
            Severity.LOW -> "Notice:"
        }
        AppLanguage.HINDI -> when (severity) {
            Severity.CRITICAL -> "तत्काल चेतावनी:"
            Severity.MODERATE -> "स्वास्थ्य चेतावनी:"
            Severity.LOW -> "सूचना:"
        }
    }

    private fun guidance(severity: Severity, language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> when (severity) {
            Severity.CRITICAL -> "Please check on the wearer now."
            Severity.MODERATE -> "Take a moment and check how you feel."
            Severity.LOW -> "No action is needed right now."
        }
        AppLanguage.HINDI -> when (severity) {
            Severity.CRITICAL -> "कृपया अभी व्यक्ति की जाँच करें।"
            Severity.MODERATE -> "कुछ देर रुककर देखें कि आप कैसा महसूस कर रहे हैं।"
            Severity.LOW -> "अभी कुछ करने की ज़रूरत नहीं है।"
        }
    }

    /** The finding's name in [language]. English matches [AnomalyType.label]. */
    fun label(type: AnomalyType, language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> type.label
        AppLanguage.HINDI -> when (type) {
            AnomalyType.LOW_SPO2 -> "रक्त में ऑक्सीजन कम"
            AnomalyType.SUSTAINED_LOW_SPO2 -> "लंबे समय से रक्त में ऑक्सीजन कम"
            AnomalyType.HIGH_HEART_RATE -> "दिल की धड़कन तेज़"
            AnomalyType.LOW_HEART_RATE -> "दिल की धड़कन धीमी"
            AnomalyType.FEVER -> "शरीर का तापमान बढ़ा हुआ"
            AnomalyType.HIGH_SKIN_TEMPERATURE -> "त्वचा का तापमान अधिक"
            AnomalyType.HEAT_STRESS -> "गर्मी से तनाव का खतरा"
            AnomalyType.DEHYDRATION_RISK -> "पानी की कमी का खतरा"
            AnomalyType.RESPIRATORY_DISTRESS -> "साँस लेने में तकलीफ़ के संकेत"
            AnomalyType.CARDIOVASCULAR_STRAIN -> "हृदय पर दबाव"
            AnomalyType.FALL_DETECTED -> "संभावित गिरावट"
            AnomalyType.FATIGUE -> "थकान के संकेत"
            AnomalyType.BASELINE_DEVIATION -> "आपके सामान्य से बदलाव"
            AnomalyType.SUSTAINED_MUSCLE_ACTIVITY -> "मांसपेशियों में लंबे समय तक अधिक तनाव"
            AnomalyType.HEART_RATE_CONTEXT -> "गतिविधि के हिसाब से धड़कन तेज़"
        }
    }
}
