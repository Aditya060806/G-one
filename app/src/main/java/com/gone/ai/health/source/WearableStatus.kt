package com.gone.ai.health.source

/**
 * The wearable's report on its own sensors, sent as the `ST` field.
 *
 * WHY THE DEVICE SAYS THIS RATHER THAN THE APP GUESSING IT
 *
 * A missing heart rate can mean four different things: the pulse sensor is not wired, it
 * is wired but not touching skin, the reading was rejected as noise, or the Bluetooth
 * line was cut short. Only the first two are something the wearer can fix, and only
 * the firmware knows which it is. Without this field the app could show nothing but a
 * blank tile, which reads as "the app is broken".
 *
 * Wire form: a hexadecimal bitmask, e.g. `ST:7F`. Unknown bits are kept in [bits] but
 * ignored, so newer firmware can add flags without breaking older apps.
 */
data class WearableStatus(val bits: Int) {

    /** The pulse oximeter (MAX30100 or MAX30102) answered on I²C at start-up. */
    val pulseOximeterPresent: Boolean get() = has(PULSE_OXIMETER_PRESENT)
    /**
     * Something is against the pulse sensor. A MAX30102 judges this from its infrared level;
     * the MAX30100 library has no such signal, so that firmware sets it while a pulse is being
     * found, which also means it stays clear for the first few seconds after the sensor is put on.
     */
    val skinContact: Boolean get() = has(SKIN_CONTACT)
    /** The DS18B20 answered on its 1-Wire bus. */
    val skinThermometerPresent: Boolean get() = has(SKIN_THERMOMETER_PRESENT)
    /** The MPU6050 answered on I²C at start-up. */
    val motionSensorPresent: Boolean get() = has(MOTION_SENSOR_PRESENT)
    /** EMG input is neither pinned at a rail nor flat: the electrodes appear attached. */
    val emgElectrodesOk: Boolean get() = has(EMG_ELECTRODES_OK)
    /** A writable SD card is mounted, so readings are buffered while out of range. */
    val sdCardPresent: Boolean get() = has(SD_CARD_PRESENT)
    /** The app's time sync has been received since power-on, so `TS` values are real. */
    val clockSynced: Boolean get() = has(CLOCK_SYNCED)

    private fun has(flag: Int) = bits and flag != 0

    /**
     * Plain-language problems the wearer can act on, most important first. Empty when
     * everything the device reports is in order.
     */
    fun problems(): List<String> = buildList {
        if (!pulseOximeterPresent) add("Heart-rate and oxygen sensor not detected — check its wiring")
        else if (!skinContact) add("Heart-rate sensor is not touching skin")
        if (!emgElectrodesOk) add("Muscle sensor pads look detached — check the electrodes")
        if (!skinThermometerPresent) add("Skin temperature sensor not detected")
        if (!motionSensorPresent) add("Motion sensor not detected — falls cannot be detected")
        if (!clockSynced) add("Wearable clock not set yet — buffered readings cannot be replayed")
    }

    companion object {
        const val PULSE_OXIMETER_PRESENT = 0x01
        const val SKIN_CONTACT = 0x02
        const val SKIN_THERMOMETER_PRESENT = 0x04
        const val MOTION_SENSOR_PRESENT = 0x08
        const val EMG_ELECTRODES_OK = 0x10
        const val SD_CARD_PRESENT = 0x20
        const val CLOCK_SYNCED = 0x40

        /** 1–4 hex digits. Anything else is malformed and yields null. */
        fun parse(raw: String): WearableStatus? {
            val text = raw.trim()
            if (text.isEmpty() || text.length > 4) return null
            if (!text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            return WearableStatus(text.toInt(16))
        }
    }
}
