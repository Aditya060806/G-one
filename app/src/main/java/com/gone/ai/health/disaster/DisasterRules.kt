package com.gone.ai.health.disaster

/**
 * What G-one does with disaster information: which feed to read, whether an alert is for the
 * wearer's district, what kind of hazard it is, what to do about it, and what the forecast alone
 * suggests. PURE KOTLIN and deterministic, like the health rules.
 */

/** SACHET's state feeds are named after the first word of the state or territory. */
object SachetFeeds {

    const val BASE = "https://sachet.ndma.gov.in/cap_public_website/rss/"

    private val SLUGS = mapOf(
        "andaman and nicobar islands" to "andaman", "andhra pradesh" to "andhra", "arunachal pradesh" to "arunachal",
        "assam" to "assam", "bihar" to "bihar", "chandigarh" to "chandigarh", "chhattisgarh" to "chhattisgarh",
        "dadra and nagar haveli and daman and diu" to "dadra", "dadra and nagar haveli" to "dadra", "daman and diu" to "dadra",
        "delhi" to "delhi", "national capital territory of delhi" to "delhi", "nct of delhi" to "delhi",
        "goa" to "goa", "gujarat" to "gujarat", "haryana" to "haryana", "himachal pradesh" to "himachal",
        "jammu and kashmir" to "jammu", "jharkhand" to "jharkhand", "karnataka" to "karnataka", "kerala" to "kerala",
        "ladakh" to "ladakh", "lakshadweep" to "lakshadweep", "madhya pradesh" to "madhya", "maharashtra" to "maharashtra",
        "manipur" to "manipur", "meghalaya" to "meghalaya", "mizoram" to "mizoram", "nagaland" to "nagaland",
        "odisha" to "odisha", "orissa" to "odisha", "puducherry" to "puducherry", "pondicherry" to "puducherry",
        "punjab" to "punjab", "rajasthan" to "rajasthan", "sikkim" to "sikkim", "tamil nadu" to "tamil",
        "telangana" to "telangana", "tripura" to "tripura", "uttar pradesh" to "uttar", "uttarakhand" to "uttarakhand",
        "uttaranchal" to "uttarakhand", "west bengal" to "west"
    )

    /** The feed for a state named as Open-Meteo's geocoder names it; null outside India's list. */
    fun urlFor(state: String?): String? {
        val key = state?.lowercase()?.replace("&", " and ")?.replace(Regex("[^a-z ]"), " ")
            ?.split(' ')?.filter { it.isNotBlank() }?.joinToString(" ") ?: return null
        return SLUGS[key]?.let { "${BASE}rss_$it.xml" }
    }

    val ALL_STATES: List<String> get() = SLUGS.keys.toList()
}

/** Whether an alert's area text names the wearer's place. */
object AreaMatch {

    /**
     * True when [areaDesc] ("Ludhiana, Patiala districts of Punjab") names any of [names]
     * (the city, the district) as a whole word, ignoring case, dots and the word "district".
     */
    fun mentions(areaDesc: String, names: List<String?>): Boolean {
        val area = " ${normalise(areaDesc)} "
        return names.mapNotNull { it?.let(::normalise) }
            .filter { it.length >= 3 }
            .any { area.contains(" $it ") }
    }

    fun normalise(text: String): String =
        text.lowercase()
            .replace(".", "")
            .replace(Regex("\\bdistricts?\\b"), " ")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .split(' ').filter { it.isNotBlank() }.joinToString(" ")
}

/** The kind of hazard, from the alert's event and headline. Decides which guidance is shown. */
enum class Hazard(val label: String) {
    HEAT("Heat"), FLOOD_RAIN("Heavy rain or flood"), THUNDERSTORM("Thunderstorm and lightning"),
    CYCLONE("Cyclone"), EARTHQUAKE("Earthquake"), COLD("Cold wave"), LANDSLIDE("Landslide"),
    TSUNAMI("Tsunami"), AIR("Air pollution"), OTHER("Other");

    companion object {
        fun of(event: String, headline: String): Hazard {
            val t = "$event $headline".lowercase()
            return when {
                "tsunami" in t -> TSUNAMI
                "cyclon" in t || "depression" in t -> CYCLONE
                "earthquake" in t -> EARTHQUAKE
                "landslide" in t || "landslip" in t -> LANDSLIDE
                "heat" in t || "hot " in t -> HEAT
                "cold wave" in t || "cold day" in t || "frost" in t -> COLD
                "thunder" in t || "lightning" in t || "squall" in t || "hail" in t || "dust storm" in t -> THUNDERSTORM
                "flood" in t || "rain" in t || "water level" in t || "river" in t || "cloudburst" in t -> FLOOD_RAIN
                "air quality" in t || "smog" in t || "pollution" in t -> AIR
                else -> OTHER
            }
        }
    }
}

/**
 * Short what-to-do lists shown with an alert, and offline. Summarised in G-one's words from the
 * National Disaster Management Authority's public "Do's and Don'ts" (ndma.gov.in); the alert's
 * own instruction, when it has one, is always shown first.
 */
object DisasterGuidance {
    const val SOURCE = "National Disaster Management Authority (NDMA) public guidance, ndma.gov.in"

    fun forHazard(hazard: Hazard): List<String> = when (hazard) {
        Hazard.HEAT -> listOf(
            "Drink water often, even when not thirsty; carry water when you go out.",
            "Stay out of the sun between 12 noon and 3 pm where you can.",
            "Wear light, loose cotton clothes and cover your head outdoors.",
            "Never leave anyone, or a pet, in a parked vehicle."
        )
        Hazard.FLOOD_RAIN -> listOf(
            "Move to higher ground if water is rising; keep away from rivers and drains.",
            "Do not walk or drive through flood water.",
            "Keep medicines, documents, a torch and drinking water ready to carry.",
            "Switch off electricity and gas if water may enter the house."
        )
        Hazard.THUNDERSTORM -> listOf(
            "Go indoors; stay away from trees, poles, metal fences and open fields.",
            "Keep away from water and from wired appliances during lightning.",
            "If caught outside, crouch low with feet together; do not lie flat."
        )
        Hazard.CYCLONE -> listOf(
            "Follow evacuation orders from the authorities without delay.",
            "Stay indoors away from windows; secure loose objects outside.",
            "Keep a radio or charged phone for official updates."
        )
        Hazard.EARTHQUAKE -> listOf(
            "Drop, cover and hold on under sturdy furniture until the shaking stops.",
            "Stay away from glass, windows and things that can fall.",
            "Outside, move to an open area away from buildings and wires."
        )
        Hazard.COLD -> listOf(
            "Wear several layers of warm clothing and keep the head and feet covered.",
            "Do not burn coal or wood in a closed room."
        )
        Hazard.LANDSLIDE -> listOf(
            "Move away from steep slopes and the paths of streams and debris.",
            "Watch for cracks, tilting trees and sudden changes in water flow."
        )
        Hazard.TSUNAMI -> listOf(
            "Move inland and to high ground at once; do not wait to see the water.",
            "Stay away from the coast until officials say it is safe."
        )
        Hazard.AIR -> listOf(
            "Limit time outdoors and avoid hard exercise outside.",
            "Keep windows closed when the air outside is worst."
        )
        Hazard.OTHER -> listOf("Follow instructions from local authorities and keep your phone charged.")
    }
}

/**
 * Hints from the weather forecast alone, using the India Meteorological Department's terms. They
 * are NOT official warnings and are shown as "from the forecast". Only the absolute heat-wave
 * criterion is used, because the departure-from-normal criterion needs a climate normal for the
 * place that the app does not have.
 */
object ForecastHazards {

    /** IMD 24-hour rainfall terms, millimetres. */
    const val HEAVY_RAIN_MM = 64.5f
    const val VERY_HEAVY_RAIN_MM = 115.6f
    const val EXTREMELY_HEAVY_RAIN_MM = 204.5f
    /** IMD heat wave on the actual maximum temperature (plains), °C. */
    const val HEAT_WAVE_C = 45f
    const val SEVERE_HEAT_WAVE_C = 47f

    data class Flag(val hazard: Hazard, val text: String, val dayIndex: Int)

    /**
     * @param dailyMaxC forecast daily maximum temperatures, today first.
     * @param dailyRainMm forecast daily rainfall totals, today first.
     */
    fun flags(dailyMaxC: List<Float>, dailyRainMm: List<Float>): List<Flag> {
        val out = mutableListOf<Flag>()
        dailyMaxC.forEachIndexed { day, t ->
            when {
                t >= SEVERE_HEAT_WAVE_C -> out += Flag(Hazard.HEAT, "Severe heat-wave level heat forecast ${dayName(day)}", day)
                t >= HEAT_WAVE_C -> out += Flag(Hazard.HEAT, "Heat-wave level heat forecast ${dayName(day)}", day)
            }
        }
        dailyRainMm.forEachIndexed { day, mm ->
            when {
                mm >= EXTREMELY_HEAVY_RAIN_MM -> out += Flag(Hazard.FLOOD_RAIN, "Extremely heavy rain forecast ${dayName(day)}", day)
                mm >= VERY_HEAVY_RAIN_MM -> out += Flag(Hazard.FLOOD_RAIN, "Very heavy rain forecast ${dayName(day)}", day)
                mm >= HEAVY_RAIN_MM -> out += Flag(Hazard.FLOOD_RAIN, "Heavy rain forecast ${dayName(day)}", day)
            }
        }
        return out
    }

    private fun dayName(day: Int) = when (day) { 0 -> "today"; 1 -> "tomorrow"; else -> "in $day days" }
}
