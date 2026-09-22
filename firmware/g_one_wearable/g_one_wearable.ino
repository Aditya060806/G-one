/*
 * G-one wearable firmware — ESP32-S3-N16R8
 *
 * Built on the bench sketch confirmed working on this board: MAX30100 on its own task, the
 * MPU6050 on a second I2C bus, DS18B20, EMG and a microSD CSV log (the SD card has since been
 * taken out of the build: see LIVE ONLY below). Its sensor code is kept as it was. What this
 * adds is the Bluetooth link the G-one app speaks. The few settings in the bench sketch that
 * stopped the app from using a reading are changed, and each change is marked CHANGED FOR THE
 * APP with the reason.
 *
 * Wiring, as built and tested:
 *
 *   MAX30100  SDA GPIO 8,  SCL GPIO 9    I2C bus 0, address 0x57
 *   MPU6050   SDA GPIO 6,  SCL GPIO 7    I2C bus 1, address 0x68 (AD0 to GND)
 *   DS18B20   DQ  GPIO 4                 4.7 kOhm from GPIO 4 to 3V3
 *   EMG       OUT GPIO 1                 ADC1_CH0; the output must never exceed 3.3 V
 *
 * Link: Bluetooth LE, HM-10 serial profile: service FFE0, characteristic FFE1, advertised as
 * "G-one Wearable". The protocol is described in docs/WEARABLE_PROTOCOL.md. In short:
 *
 *   Live:      one line twice a second, a field left out when it was not measured
 *              HR:72,SPO2:97,STEMP:33.90,MOT:1.02,EMG:1234,EMGPK:1810,EMGBITS:12,ST:7F,TS:1726470000000
 *
 *   Command:   T:<epoch ms> sets the clock, so each line carries the time it was taken (TS).
 *
 * LIVE ONLY. There is no SD card on this build and nothing is kept on the board: each line goes to
 * the phone as it is made, and one made while the phone is out of range is lost, leaving a gap in
 * the app's record. The phone's ACK and STOP commands, which a wearable that keeps readings uses
 * to replay them and to pause, are ignored. docs/WEARABLE_PROTOCOL.md describes that fuller
 * protocol; the app still speaks it, so a card-backed wearable would work unchanged.
 *
 * Board settings (Arduino IDE > Tools, or arduino-cli):
 *   Board "ESP32S3 Dev Module", Flash Size 16MB, PSRAM "OPI PSRAM",
 *   Partition Scheme "16M Flash (3MB APP/9.9MB FATFS)", USB CDC On Boot "Enabled" on the
 *   board's native USB port.
 *   arduino-cli: --fqbn esp32:esp32:esp32s3:FlashSize=16M,FlashMode=qio,PSRAM=opi,PartitionScheme=app3M_fat9M_16MB,CDCOnBoot=cdc
 *
 * Libraries (Arduino Library Manager): MAX30100lib (OXullo Intersecans), NimBLE-Arduino 2.x
 * (h2zero), OneWire, DallasTemperature. Board package: esp32 by Espressif.
 * Compiled with esp32 3.3.11, MAX30100lib 1.2.1, NimBLE-Arduino 2.5.1, OneWire 2.3.8,
 * DallasTemperature 4.0.6.
 */

#include <Wire.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <NimBLEDevice.h>
#include <algorithm>
#include "MAX30100_PulseOximeter.h"
#include "esp_timer.h"


// =====================================================
//                    PIN CONFIGURATION
// =====================================================

// ---------------- MAX30100 ----------------
#define MAX_SDA 8
#define MAX_SCL 9

// ---------------- MPU6050 ----------------
#define MPU_SDA 6
#define MPU_SCL 7
#define MPU_ADDR 0x68

// ---------------- DS18B20 ----------------
#define TEMP_PIN 4

// ---------------- EMG ----------------
#define EMG_PIN 1


// =====================================================
//                    MAX30100
// =====================================================

PulseOximeter pox;

bool maxOK = false;

volatile float heartRate = 0.0;
volatile uint8_t spo2 = 0;

TaskHandle_t max30100TaskHandle = NULL;

// A MAX30100 that does not answer is asked again this often,
// without restarting the board.
const uint32_t MAX_RETRY_MS = 5000;
uint32_t lastMaxRetryAt = 0;

// Times of the last beats the library detected, written from its
// callback on core 0 and read by the loop on core 1. Only ever
// touched while holding beatMux, which also orders the memory.
static const uint8_t BEAT_HISTORY = 5;
uint32_t beatTimes[BEAT_HISTORY];
uint8_t beatHead = 0;
uint8_t beatsSeen = 0;
portMUX_TYPE beatMux = portMUX_INITIALIZER_UNLOCKED;


// =====================================================
//                    MPU6050
// =====================================================

TwoWire MPU_I2C = TwoWire(1);

bool mpuOK = false;

float ax = 0.0;
float ay = 0.0;
float az = 0.0;

float gx = 0.0;
float gy = 0.0;
float gz = 0.0;

float mpuTemp = 0.0;


// =====================================================
//                    DS18B20
// =====================================================

OneWire oneWire(TEMP_PIN);
DallasTemperature tempSensor(&oneWire);

bool tempOK = false;

float skinTemp = 0.0;

bool tempConversionRunning = false;
uint32_t tempRequestTime = 0;

const uint32_t TEMP_CONVERSION_TIME = 800;


// =====================================================
//                    EMG
// =====================================================

bool emgOK = false;

int emgADC = 0;
float emgVoltage = 0.0;


// =====================================================
//                    TIMING
// =====================================================

uint32_t lastPrint = 0;

const uint32_t PRINT_INTERVAL = 1000;


// =====================================================
//              THE APP LINK: CONFIGURATION
// =====================================================

// Choose to match the EMG module's output:
//   EMG_MODE_ENVELOPE — the module outputs a rectified, smoothed envelope (e.g. MyoWare's
//                       ENV/SIG output). The line reports the mean and peak of that envelope.
//   EMG_MODE_RAW_RMS  — the module outputs the raw signal centred at mid-rail. The line
//                       reports RMS amplitude around the mean, scaled ×2 to use the range.
// Either way the numbers are uncalibrated; calibrate them in the app on the wearer.
#define EMG_MODE_ENVELOPE 1
#define EMG_MODE_RAW_RMS  2
#ifndef EMG_MODE
#define EMG_MODE EMG_MODE_ENVELOPE
#endif

static const char*    DEVICE_NAME          = "G-one Wearable";

static const uint32_t LINE_INTERVAL_MS     = 500;     // two lines a second
static const uint32_t PULSE_HOLD_MS        = 3000;    // "touching skin" outlasts the gap between beats
static const uint32_t BEAT_TIMEOUT_MS      = 2500;    // no beat for this long: the heart rate is not current
static const uint32_t READING_STALE_MS     = 5000;    // a sensor silent this long is not sent as current
static const int      EMG_RAIL             = 4090;    // matches the app's VitalsSample.EMG_RAIL

// Status bits — must match com.gone.ai.health.source.WearableStatus.
static const uint8_t ST_PULSE_OXIMETER = 0x01;
static const uint8_t ST_SKIN_CONTACT   = 0x02;
static const uint8_t ST_SKIN_THERMO    = 0x04;
static const uint8_t ST_MOTION_SENSOR  = 0x08;
static const uint8_t ST_EMG_OK         = 0x10;
static const uint8_t ST_SD_CARD        = 0x20;   // never set: this build has no card
static const uint8_t ST_CLOCK_SYNCED   = 0x40;


// =====================================================
//              THE APP LINK: STATE
// =====================================================

NimBLEServer*         bleServer = nullptr;
NimBLECharacteristic* serialChar = nullptr;

volatile bool     connected = false;
volatile bool     clockSynced = false;
volatile int64_t  epochOffsetMs = 0;    // epoch ms = uptime ms + offset
uint16_t          peerMtu = 23;

// What the next line reports, gathered since the previous one.
float    motionPeakG = NAN;
uint32_t lastMpuOkAt = 0;
uint32_t lastTempOkAt = 0;
uint32_t emgCount = 0, emgRailCount = 0, emgZeroCount = 0;
uint64_t emgSum = 0;
double   emgSumSq = 0;
uint16_t emgMax = 0;
String   lastSentLine;

bool     partialLineSent = false;   // a notification failed mid-line; start the next send on a fresh line

uint32_t lastLineAt = 0;


// =====================================================
//                MAX30100 CALLBACK
// =====================================================

void onBeatDetected()
{
    // Callback intentionally kept lightweight.
    // Do not perform Serial/I2C/SD work here.

    // For the app: only the time of the beat, so the heart
    // rate can be checked against real beats (checkedHeartRate).
    uint32_t now = millis();

    portENTER_CRITICAL(&beatMux);
    beatTimes[beatHead] = now;
    beatHead = (beatHead + 1) % BEAT_HISTORY;
    if (beatsSeen < BEAT_HISTORY) beatsSeen++;
    portEXIT_CRITICAL(&beatMux);
}


// =====================================================
//              MAX30100 DEDICATED TASK
// =====================================================
//
// This task runs independently from the normal
// Arduino loop.
//
// MAX30100 update processing therefore does not
// get blocked by:
//   - DS18B20
//   - MPU6050
//   - EMG
//   - Serial printing
//   - Bluetooth sends
//
// The Bluetooth host task also runs on core 0, at a
// higher priority, so it is never starved by this one.
//
// =====================================================

void max30100Task(void *parameter)
{
    while (true)
    {
        if (maxOK)
        {
            // CRITICAL:
            // Keep this running continuously.
            pox.update();

            // Read the processed values.
            heartRate = pox.getHeartRate();
            spo2 = pox.getSpO2();
        }

        // Very short task yield.
        vTaskDelay(pdMS_TO_TICKS(1));
    }
}


// =====================================================
//              START MAX30100 TASK
// =====================================================

void startMAX30100Task()
{
    xTaskCreatePinnedToCore(
        max30100Task,
        "MAX30100",
        4096,
        NULL,
        3,
        &max30100TaskHandle,
        0
    );
}


// =====================================================
//              MAX30100 BUS CHECK
// =====================================================
//
// Many MAX30100 boards pull SDA and SCL up to their own
// 1.8 V regulator, not to 3.3 V. The ESP32-S3 needs about
// 2.5 V to read a 1, so such a sensor answers on some
// boots and not others, and gives unreliable samples when
// it does. This measures the bus before anything drives
// it, and says so. 2.2 kOhm from SDA and from SCL to 3V3
// lifts the bus clear of it.
//
// =====================================================

void reportMaxBusLevels()
{
    // Runs before anything else has set the ADC up: without this
    // its range can stop short of the levels being measured.
    analogReadResolution(12);
    analogSetAttenuation(ADC_11db);

    pinMode(MAX_SDA, INPUT);
    pinMode(MAX_SCL, INPUT);
    delay(5);

    uint32_t sda = 0, scl = 0;

    for (int i = 0; i < 16; i++)
    {
        sda += analogReadMilliVolts(MAX_SDA);
        scl += analogReadMilliVolts(MAX_SCL);
    }

    sda /= 16;
    scl /= 16;

    Serial.printf(
        "MAX30100 bus idles at SDA %lu mV, SCL %lu mV\n",
        (unsigned long)sda, (unsigned long)scl
    );

    // The ADC tops out a little below the supply: a line held at
    // 3.3 V reads about 2.2-2.3 V on this board, one at the
    // module's 1.8 V reads about 1.8 V.
    if (sda < 300 || scl < 300)
    {
        Serial.println(
            "  -> nothing holds the bus up: is the MAX30100 connected to GPIO 8 and 9?"
        );
    }
    else if (sda < 2000 || scl < 2000)
    {
        Serial.println(
            "  -> TOO LOW: the module pulls its bus to 1.8 V. Running it at 100 kHz to cope; for full"
        );

        Serial.println(
            "     reliability add 1-2.2 kOhm from SDA to 3V3 and from SCL to 3V3"
        );
    }
}


// =====================================================
//              INITIALIZE MAX30100
// =====================================================

bool initMAX30100()
{
    Serial.println();
    Serial.println("Initializing MAX30100...");

    // IMPORTANT:
    // MAX30100_PulseOximeter library uses GLOBAL Wire.

    Wire.begin(
        MAX_SDA,
        MAX_SCL,
        400000
    );

    delay(100);

    if (!pox.begin())
    {
        Serial.println(
            "MAX30100 INITIALIZATION FAILED"
        );

        return false;
    }

    Serial.println(
        "MAX30100 INITIALIZED"
    );

    // Same setting as the known-working
    // standalone configuration.
    pox.setIRLedCurrent(
        MAX30100_LED_CURR_50MA
    );

    pox.setOnBeatDetectedCallback(
        onBeatDetected
    );

    // CHANGED FOR THE APP: 100 kHz, not the library's 400 kHz.
    // This module pulls its bus up weakly, to 1.8 V: each line
    // rises too slowly for 400 kHz, and a read goes wrong now and
    // then. At 100 kHz it has four times as long. The sensor sends
    // a few hundred bytes a second, well within 100 kHz.
    // (pox.begin() sets 400 kHz itself, so this comes after it.)
    Wire.setClock(100000);

    return true;
}


// =====================================================
//                  MPU WRITE
// =====================================================

void mpuWrite(uint8_t reg, uint8_t value)
{
    MPU_I2C.beginTransmission(MPU_ADDR);
    MPU_I2C.write(reg);
    MPU_I2C.write(value);
    MPU_I2C.endTransmission();
}


// =====================================================
//                  MPU READ REGISTER
// =====================================================

uint8_t mpuReadRegister(uint8_t reg)
{
    MPU_I2C.beginTransmission(MPU_ADDR);
    MPU_I2C.write(reg);

    if (MPU_I2C.endTransmission(false) != 0)
    {
        return 0xFF;
    }

    uint8_t count =
        MPU_I2C.requestFrom(
            MPU_ADDR,
            (uint8_t)1
        );

    if (count == 1 &&
        MPU_I2C.available())
    {
        return MPU_I2C.read();
    }

    return 0xFF;
}


// =====================================================
//                  MPU READ DATA
// =====================================================

bool readMPU()
{
    MPU_I2C.beginTransmission(MPU_ADDR);
    MPU_I2C.write(0x3B);

    if (MPU_I2C.endTransmission(false) != 0)
    {
        return false;
    }

    uint8_t received =
        MPU_I2C.requestFrom(
            MPU_ADDR,
            (uint8_t)14
        );

    if (received != 14)
    {
        return false;
    }

    int16_t rawAX =
        ((int16_t)MPU_I2C.read() << 8) |
        MPU_I2C.read();

    int16_t rawAY =
        ((int16_t)MPU_I2C.read() << 8) |
        MPU_I2C.read();

    int16_t rawAZ =
        ((int16_t)MPU_I2C.read() << 8) |
        MPU_I2C.read();

    int16_t rawTemp =
        ((int16_t)MPU_I2C.read() << 8) |
        MPU_I2C.read();

    int16_t rawGX =
        ((int16_t)MPU_I2C.read() << 8) |
        MPU_I2C.read();

    int16_t rawGY =
        ((int16_t)MPU_I2C.read() << 8) |
        MPU_I2C.read();

    int16_t rawGZ =
        ((int16_t)MPU_I2C.read() << 8) |
        MPU_I2C.read();


    // CHANGED FOR THE APP: ±8 g, 4096 counts per g (was ±2 g).
    ax = rawAX / 4096.0f;
    ay = rawAY / 4096.0f;
    az = rawAZ / 4096.0f;


    // ±250 °/s
    gx = rawGX / 131.0f;
    gy = rawGY / 131.0f;
    gz = rawGZ / 131.0f;


    // MPU internal temperature
    mpuTemp =
        (rawTemp / 340.0f) + 36.53f;


    return true;
}


// =====================================================
//              RETRY MAX30100
// =====================================================

void retryMAX30100()
{
    if (maxOK || millis() - lastMaxRetryAt < MAX_RETRY_MS)
    {
        return;
    }

    lastMaxRetryAt = millis();

    maxOK = initMAX30100();

    if (maxOK)
    {
        startMAX30100Task();
    }
}


// =====================================================
//              INITIALIZE MPU6050
// =====================================================

bool initMPU()
{
    Serial.println();
    Serial.println("Initializing MPU6050...");

    MPU_I2C.begin(
        MPU_SDA,
        MPU_SCL,
        400000
    );

    delay(100);


    MPU_I2C.beginTransmission(MPU_ADDR);

    if (MPU_I2C.endTransmission() != 0)
    {
        Serial.println(
            "MPU6050 NOT FOUND"
        );

        return false;
    }

    Serial.println(
        "MPU6050 FOUND at 0x68"
    );


    // Wake sensor
    mpuWrite(0x6B, 0x00);

    delay(100);


    // CHANGED FOR THE APP: accelerometer ±8 g (was ±2 g, 0x00).
    // The app treats a peak of 2.5 g as a possible fall; at ±2 g the
    // reading clips at 2 g, so no fall could ever be detected.
    mpuWrite(0x1C, 0x10);


    // Gyroscope ±250°/s
    mpuWrite(0x1B, 0x00);


    // Digital low-pass filter
    mpuWrite(0x1A, 0x03);


    // Sample rate divider
    mpuWrite(0x19, 0x04);


    delay(50);


    // Actual data read verification
    if (readMPU())
    {
        Serial.println(
            "MPU6050 READY"
        );

        return true;
    }


    Serial.println(
        "MPU6050 DATA READ FAILED"
    );

    return false;
}


// =====================================================
//              INITIALIZE DS18B20
// =====================================================

bool initTemperature()
{
    Serial.println();
    Serial.println("Initializing DS18B20...");

    tempSensor.begin();

    int count =
        tempSensor.getDeviceCount();

    Serial.print(
        "DS18B20 devices found: "
    );

    Serial.println(count);


    if (count <= 0)
    {
        Serial.println(
            "DS18B20 NOT FOUND"
        );

        return false;
    }


    // Non-blocking conversion
    tempSensor.setWaitForConversion(false);


    Serial.println(
        "DS18B20 READY"
    );


    // Start first conversion
    tempSensor.requestTemperatures();

    tempRequestTime = millis();

    tempConversionRunning = true;


    return true;
}


// =====================================================
//              UPDATE DS18B20
// =====================================================

void updateTemperature()
{
    if (!tempOK)
    {
        return;
    }


    if (tempConversionRunning)
    {
        if (millis() - tempRequestTime >=
            TEMP_CONVERSION_TIME)
        {
            float value =
                tempSensor.getTempCByIndex(0);


            if (value != DEVICE_DISCONNECTED_C &&
                value > -55.0 &&
                value < 125.0)
            {
                skinTemp = value;

                // CHANGED FOR THE APP: remember when the reading was
                // last good, so a sensor that fails later is not
                // reported with its old value forever.
                lastTempOkAt = millis();
            }


            // Immediately start next conversion
            tempSensor.requestTemperatures();

            tempRequestTime = millis();

            tempConversionRunning = true;
        }
    }
}


// =====================================================
//                  INITIALIZE EMG
// =====================================================

void initEMG()
{
    Serial.println();
    Serial.println("Initializing EMG ADC...");

    analogReadResolution(12);

    pinMode(
        EMG_PIN,
        INPUT
    );


    analogSetPinAttenuation(
        EMG_PIN,
        ADC_11db
    );


    emgADC =
        analogRead(EMG_PIN);

    emgVoltage =
        analogReadMilliVolts(EMG_PIN) / 1000.0f;


    Serial.print(
        "Initial EMG ADC: "
    );

    Serial.println(emgADC);

    Serial.println(
        "EMG ADC READY"
    );


    emgOK = true;
}


// =====================================================
//                  READ EMG
// =====================================================

void readEMG()
{
    if (!emgOK)
    {
        return;
    }


    emgADC =
        analogRead(EMG_PIN);


    // CHANGED: the chip's own calibration, not ADC/4095 × 3.3 V. At this
    // attenuation full scale is about 3.1 V and the ADC is not linear near
    // either end, so the straight-line formula read high.
    emgVoltage =
        analogReadMilliVolts(EMG_PIN) / 1000.0f;


    // For the app: every sample since the last line.
    uint16_t v = (uint16_t)emgADC;
    emgCount++;
    emgSum += v;
    emgSumSq += (double)v * v;
    if (v > emgMax) emgMax = v;
    if (v >= EMG_RAIL) emgRailCount++;
    if (v == 0) emgZeroCount++;
}


// =====================================================
//              THE APP LINK: TIME
// =====================================================

int64_t uptimeMs() { return esp_timer_get_time() / 1000; }   // 64-bit; millis() wraps after 49 days
int64_t epochMs()  { return uptimeMs() + epochOffsetMs; }


// =====================================================
//              THE APP LINK: BLUETOOTH
// =====================================================

// NimBLE 2.x passes NimBLEConnInfo to these. A callback written for 1.x (onConnect with the
// server alone) compiles but overrides nothing, so it is never called.
class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& info) override {
    connected = true;
    peerMtu = server->getPeerMTU(info.getConnHandle());
  }
  void onMTUChange(uint16_t mtu, NimBLEConnInfo& info) override {
    peerMtu = mtu;
  }
  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& info, int reason) override {
    connected = false;
    NimBLEDevice::startAdvertising();
  }
};

class SerialCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo& info) override {
    std::string value = characteristic->getValue();
    if (value.size() > 2 && value[0] == 'T' && value[1] == ':') {
      int64_t epoch = strtoll(value.c_str() + 2, nullptr, 10);
      if (epoch > 1600000000000LL) {   // sanity: after Sept 2020
        epochOffsetMs = epoch - uptimeMs();
        clockSynced = true;
      }
    }
    // ACK and STOP are for a wearable that keeps readings. With no card there is nothing to
    // acknowledge or pause, so they are ignored.
  }
};

void setupBle() {
  NimBLEDevice::init(DEVICE_NAME);
  NimBLEDevice::setMTU(185);
  // 3 dBm, not the default 9: a phone within a few metres needs no more, and the radio's
  // current spikes are one cause of the brown-out restarts.
  NimBLEDevice::setPower(3);
  bleServer = NimBLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  NimBLEService* service = bleServer->createService("FFE0");
  serialChar = service->createCharacteristic(
      "FFE1",
      NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  serialChar->setCallbacks(new SerialCallbacks());

  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  advertising->setName(DEVICE_NAME);
  advertising->addServiceUUID("FFE0");   // lets the app list this device first
  advertising->enableScanResponse(true);
  advertising->start();
}

/**
 * Notify in chunks that fit the negotiated MTU; the app reassembles lines. A single notify of
 * a long line would be cut to MTU − 3 bytes.
 * Returns false when a notification could not be queued, so the caller keeps the line.
 */
bool sendText(const String& text) {
  if (!connected || serialChar == nullptr) return false;
  String out = partialLineSent ? "\n" + text : text;   // end the half-sent line; the app drops it
  const size_t chunk = (peerMtu > 3 ? peerMtu - 3 : 20);
  const char* data = out.c_str();
  for (size_t offset = 0; offset < out.length(); offset += chunk) {
    size_t len = min(chunk, out.length() - offset);
    if (!serialChar->notify(static_cast<const uint8_t*>(static_cast<const void*>(data + offset)), len)) {
      partialLineSent = offset > 0 || partialLineSent;
      return false;
    }
    delay(4);   // give the stack room; notifications are not queued indefinitely
  }
  partialLineSent = false;
  return true;
}


// =====================================================
//              THE APP LINK: LINES
// =====================================================

uint8_t statusBits(bool emgSignalOk, bool pulse) {
  uint8_t status = 0;
  if (maxOK)       status |= ST_PULSE_OXIMETER;
  if (pulse)       status |= ST_SKIN_CONTACT;
  if (tempOK)      status |= ST_SKIN_THERMO;
  if (mpuOK)       status |= ST_MOTION_SENSOR;
  if (emgSignalOk) status |= ST_EMG_OK;
  if (clockSynced) status |= ST_CLOCK_SYNCED;
  return status;
}

/** The library's recent beat times, oldest first. Returns how many there are. */
uint8_t recentBeats(uint32_t* out) {
  portENTER_CRITICAL(&beatMux);
  uint8_t n = beatsSeen;
  for (uint8_t i = 0; i < n; i++) out[i] = beatTimes[(beatHead + BEAT_HISTORY - n + i) % BEAT_HISTORY];
  portEXIT_CRITICAL(&beatMux);
  return n;
}

/** A beat time counts as within `limit` of `now` even when it is a moment newer than `now`. */
bool beatWithin(uint32_t now, uint32_t beat, uint32_t limit) {
  return (int32_t)(now - beat) < (int32_t)limit;
}

/**
 * "Touching skin": the library found a beat in the last few seconds. The MAX30100 library has
 * no finger-present signal, and its heart rate is no guide either: see checkedHeartRate.
 */
bool pulseDetected(uint32_t now) {
  uint32_t beats[BEAT_HISTORY];
  uint8_t n = recentBeats(beats);
  return n > 0 && beatWithin(now, beats[n - 1], PULSE_HOLD_MS);
}

/**
 * The library's heart rate, or 0 when the beats it has just found do not back it up.
 *
 * Found on the bench: the library resets its rate to 0 from only one internal state, so once
 * its detector sticks — after the board is knocked, say — it goes on reporting its last rate
 * with nothing on the sensor (0.3 BPM here; after a real pulse, that pulse, indefinitely). And
 * its first few rates after the sensor is touched still average in the time before it was.
 * So a rate is sent only with BEAT_HISTORY beats found, the newest within BEAT_TIMEOUT_MS, and
 * within 20 % of the rate those beats imply.
 */
int checkedHeartRate(uint32_t now) {
  uint32_t beats[BEAT_HISTORY];
  uint8_t n = recentBeats(beats);
  if (n < BEAT_HISTORY || !beatWithin(now, beats[n - 1], BEAT_TIMEOUT_MS)) return 0;
  uint32_t gaps[BEAT_HISTORY - 1];
  for (uint8_t i = 0; i + 1 < n; i++) gaps[i] = beats[i + 1] - beats[i];
  std::sort(gaps, gaps + n - 1);
  float median = (gaps[(n - 2) / 2] + gaps[(n - 1) / 2]) / 2.0f;
  if (median < 200.0f || median > 3000.0f) return 0;   // outside 20–300 beats a minute
  float fromBeats = 60000.0f / median;
  float reported = heartRate;
  if (fabsf(reported - fromBeats) > 0.2f * fromBeats) return 0;
  return (int)lroundf(reported);
}

/** The reading fields and status; TS is added by the caller when the clock is set. */
String buildLine(uint32_t now) {
  String line;
  line.reserve(128);
  auto field = [&line](const String& f) {
    if (line.length() > 0) line += ',';
    line += f;
  };

  // A reading is sent only when it was measured just now; a missing field means "not
  // measured". The ranges are the app's own, so nothing sent is thrown away there.
  bool pulse = maxOK && pulseDetected(now);
  int hr = maxOK ? checkedHeartRate(now) : 0;   // whole beats a minute, which is what the app takes
  if (hr >= 20 && hr <= 300) {
    field("HR:" + String(hr));
    // The library's SpO2 comes from the same beats, so it is only as good as the heart rate.
    uint8_t oxygen = spo2;
    if (oxygen >= 50 && oxygen <= 100) field("SPO2:" + String(oxygen));
  }
  if (tempOK && lastTempOkAt != 0 && now - lastTempOkAt < READING_STALE_MS &&
      skinTemp >= 20.0f && skinTemp <= 42.0f) {
    field("STEMP:" + String(skinTemp, 2));
  }
  if (mpuOK && !isnan(motionPeakG) && now - lastMpuOkAt < READING_STALE_MS) {
    field("MOT:" + String(motionPeakG, 2));
  }

  bool emgSignalOk = false;
  if (emgCount > 0) {
    uint32_t mean = emgSum / emgCount;
    uint32_t level, peak;
#if EMG_MODE == EMG_MODE_RAW_RMS
    double variance = emgSumSq / emgCount - (double)mean * mean;
    double rms = variance > 0 ? sqrt(variance) : 0;
    level = min<uint32_t>(4095, (uint32_t)(rms * 2.0));
    peak = min<uint32_t>(4095, max<uint32_t>(level, (emgMax > mean ? emgMax - mean : 0) * 2u));
    emgSignalOk = emgRailCount < emgCount * 9 / 10 && rms >= 3.0;   // flat means no signal reaching the ADC
#else
    level = mean;
    peak = emgMax;
    emgSignalOk = emgRailCount < emgCount * 9 / 10 && emgZeroCount < emgCount;
#endif
    field("EMG:" + String(level));
    field("EMGPK:" + String(peak));
    field("EMGBITS:12");
  }

  char st[8];
  snprintf(st, sizeof(st), "ST:%02X", statusBits(emgSignalOk, pulse));
  field(st);

  // Start gathering the next line.
  motionPeakG = NAN;
  emgCount = emgRailCount = emgZeroCount = 0;
  emgSum = 0;
  emgSumSq = 0;
  emgMax = 0;
  return line;
}


// =====================================================
//              THE APP LINK: SENDING
// =====================================================

/**
 * Live only: a line goes to the phone as it is made, or nowhere. Nothing is kept on the board,
 * so a line made while the phone is away, or one the link cannot take, is not sent later.
 */
void emitLine(const String& line) {
  if (!connected) return;
  String live = clockSynced ? line + ",TS:" + String((long long)epochMs()) : line;
  if (sendText(live + "\n")) lastSentLine = live;
}


// =====================================================
//                  PRINT SENSOR DATA
// =====================================================

void printSensorData()
{
    Serial.println();
    Serial.println(
        "========================================"
    );


    // -----------------------------------------
    // MAX30100
    // -----------------------------------------

    Serial.print(
        "HR: "
    );

    float hr =
        (float)heartRate;

    uint8_t oxygen =
        (uint8_t)spo2;


    if (hr > 0.0)
    {
        Serial.print(
            hr,
            1
        );

        Serial.println(
            " BPM"
        );
    }
    else
    {
        Serial.println(
            "0 BPM"
        );
    }


    Serial.print(
        "SpO2: "
    );


    if (oxygen > 0)
    {
        Serial.print(
            oxygen
        );

        Serial.println(
            " %"
        );
    }
    else
    {
        Serial.println(
            "0 %"
        );
    }


    // -----------------------------------------
    // DS18B20
    // -----------------------------------------

    Serial.print(
        "Skin Temp: "
    );

    if (tempOK)
    {
        Serial.print(
            skinTemp,
            2
        );

        Serial.println(
            " C"
        );
    }
    else
    {
        Serial.println(
            "-- C"
        );
    }


    // -----------------------------------------
    // EMG
    // -----------------------------------------

    Serial.print(
        "EMG: "
    );

    Serial.print(
        emgADC
    );

    Serial.print(
        " ADC | "
    );

    Serial.print(
        emgVoltage,
        3
    );

    Serial.println(
        " V"
    );


    // -----------------------------------------
    // MPU6050
    // -----------------------------------------

    if (mpuOK)
    {
        Serial.print(
            "ACC: "
        );

        Serial.print(
            ax,
            2
        );

        Serial.print(
            " , "
        );

        Serial.print(
            ay,
            2
        );

        Serial.print(
            " , "
        );

        Serial.print(
            az,
            2
        );

        Serial.println(
            " g"
        );


        Serial.print(
            "GYRO: "
        );

        Serial.print(
            gx,
            1
        );

        Serial.print(
            " , "
        );

        Serial.print(
            gy,
            1
        );

        Serial.print(
            " , "
        );

        Serial.print(
            gz,
            1
        );

        Serial.println(
            " deg/s"
        );
    }
    else
    {
        Serial.println(
            "ACC: --"
        );

        Serial.println(
            "GYRO: --"
        );
    }


    // -----------------------------------------
    // THE APP LINK
    // -----------------------------------------

    Serial.printf(
        "APP: %s | clock %s | live only, nothing stored\n",
        connected ? "CONNECTED" : "not connected (advertising as G-one Wearable)",
        clockSynced ? "set" : "not set"
    );

    // Exactly what the app was sent, so a value on the phone can be checked against this.
    Serial.print("SENT: ");
    Serial.println(lastSentLine.length() > 0 ? lastSentLine : String("(nothing yet)"));


    Serial.println(
        "========================================"
    );
}


// =====================================================
//                         SETUP
// =====================================================

void setup()
{
    // CHANGED FOR THE APP: 80 MHz, not 240. The sensors and the
    // Bluetooth link need a fraction of that, and the lower clock
    // cuts the board's own current draw and heat, one cause of the
    // brown-out restarts. Set first, before anything times itself.
    setCpuFrequencyMhz(80);

    // CHANGED FOR THE APP: on the board's own USB port, a PC that has
    // the port closed stops reading, and each print then waits up to
    // two seconds for it. That froze the loop long enough for the app
    // to give up on the link. A larger buffer and no waiting keep the
    // prints from ever holding the loop up.
#if ARDUINO_USB_CDC_ON_BOOT && ARDUINO_USB_MODE
    Serial.setTxBufferSize(4096);
#endif

    Serial.begin(115200);

#if ARDUINO_USB_CDC_ON_BOOT && ARDUINO_USB_MODE
    Serial.setTxTimeoutMs(0);
#endif

    delay(2500);


    Serial.println();
    Serial.println(
        "========================================"
    );

    Serial.println(
        "             G-ONE WEARABLE"
    );

    Serial.println(
        "          ESP32-S3 N16R8"
    );

    Serial.println(
        "========================================"
    );


    Serial.println();
    Serial.println(
        "Starting G-one hardware..."
    );


    // =================================================
    // MAX30100
    // =================================================

    reportMaxBusLevels();

    // CHANGED FOR THE APP: asked up to three times, since a
    // sensor on a weak bus can miss the first request; the loop
    // keeps asking after that (retryMAX30100).
    for (int attempt = 0; attempt < 3 && !maxOK; attempt++)
    {
        maxOK = initMAX30100();

        if (!maxOK)
        {
            delay(200);
        }
    }


    // =================================================
    // MPU6050
    // =================================================

    mpuOK =
        initMPU();


    // =================================================
    // DS18B20
    // =================================================

    tempOK =
        initTemperature();


    // =================================================
    // EMG
    // =================================================

    initEMG();


    // =================================================
    // START MAX30100 DEDICATED TASK
    // =================================================

    if (maxOK)
    {
        startMAX30100Task();

        Serial.println();
        Serial.println(
            "MAX30100 background processing started"
        );
    }


    // =================================================
    // BLUETOOTH
    // =================================================

    setupBle();

    Serial.println();
    Serial.println(
        "Bluetooth advertising as G-one Wearable"
    );


    // =================================================
    // STATUS
    // =================================================

    Serial.println();
    Serial.println(
        "========================================"
    );

    Serial.println(
        "             G-ONE STATUS"
    );

    Serial.println(
        "========================================"
    );


    Serial.print(
        "MAX30100 : "
    );

    Serial.println(
        maxOK
            ? "READY"
            : "UNAVAILABLE"
    );


    Serial.print(
        "MPU6050  : "
    );

    Serial.println(
        mpuOK
            ? "READY"
            : "UNAVAILABLE"
    );


    Serial.print(
        "DS18B20  : "
    );

    Serial.println(
        tempOK
            ? "READY"
            : "UNAVAILABLE"
    );


    Serial.print(
        "EMG      : "
    );

    Serial.println(
        emgOK
            ? "READY"
            : "UNAVAILABLE"
    );


    Serial.println(
        "App link : live only, nothing is stored on the board"
    );


    Serial.println(
        "========================================"
    );


    Serial.println();
    Serial.println(
        "G-one live monitoring started."
    );

    Serial.println();
}


// =====================================================
//                         LOOP
// =====================================================

void loop()
{
    // =================================================
    // IMPORTANT:
    //
    // MAX30100 is NO LONGER updated here.
    //
    // It is being continuously updated by its
    // dedicated background task on the other CPU core.
    // =================================================


    // A MAX30100 that did not answer at start-up.
    retryMAX30100();


    // =================================================
    // MPU6050
    // =================================================

    if (mpuOK && readMPU())
    {
        // For the app: the hardest movement since the last line.
        // A peak, not the latest value, so an impact is not missed.
        float g = sqrtf(ax * ax + ay * ay + az * az);

        if (isnan(motionPeakG) || g > motionPeakG)
        {
            motionPeakG = g;
        }

        lastMpuOkAt = millis();
    }


    // =================================================
    // DS18B20
    // =================================================

    updateTemperature();


    // =================================================
    // EMG
    // =================================================

    readEMG();


    // =================================================
    // THE APP: one line twice a second, sent as it
    // is made.
    // =================================================

    uint32_t now = millis();

    if (now - lastLineAt >= LINE_INTERVAL_MS)
    {
        lastLineAt = now;

        emitLine(buildLine(now));
    }


    // =================================================
    // SERIAL
    // =================================================

    if (millis() - lastPrint >=
        PRINT_INTERVAL)
    {
        lastPrint =
            millis();

        printSensorData();
    }


    // Main loop can yield normally.
    // MAX30100 is independent.
    delay(2);
}
