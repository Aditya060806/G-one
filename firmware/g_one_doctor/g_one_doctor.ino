#include <Wire.h>
#include <SPI.h>
#include <SD.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include "MAX30100_PulseOximeter.h"

// =====================================================
//                    G-ONE WEARABLE
//              ESP32-S3 N16R8 FINAL FIRMWARE
// =====================================================
//
// MAX30100  -> Heart Rate + SpO2
// MPU6050   -> Accelerometer + Gyroscope
// DS18B20   -> Skin Temperature
// EMG       -> Muscle electrical activity
// microSD   -> Local CSV logging
//
// MAX30100 gets its own dedicated task so pox.update()
// runs continuously and is not interrupted by other
// sensor processing or SD operations.
// =====================================================


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

// ---------------- microSD ----------------
#define SD_CS   10
#define SD_MOSI 11
#define SD_SCK  12
#define SD_MISO 13


// =====================================================
//                    MAX30100
// =====================================================

PulseOximeter pox;

bool maxOK = false;

volatile float heartRate = 0.0;
volatile uint8_t spo2 = 0;

TaskHandle_t max30100TaskHandle = NULL;


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
//                    microSD
// =====================================================

SPIClass sdSPI(FSPI);

bool sdOK = false;


// =====================================================
//                    TIMING
// =====================================================

uint32_t lastPrint = 0;
uint32_t lastSDWrite = 0;

const uint32_t PRINT_INTERVAL = 1000;
const uint32_t SD_INTERVAL = 1000;


// =====================================================
//                MAX30100 CALLBACK
// =====================================================

void onBeatDetected()
{
    // Callback intentionally kept lightweight.
    // Do not perform Serial/I2C/SD work here.
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
//   - microSD
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


    // ±2g
    ax = rawAX / 16384.0f;
    ay = rawAY / 16384.0f;
    az = rawAZ / 16384.0f;


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


    // Accelerometer ±2g
    mpuWrite(0x1C, 0x00);


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
        (emgADC / 4095.0f) * 3.3f;


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


    emgVoltage =
        (emgADC / 4095.0f) * 3.3f;
}


// =====================================================
//                INITIALIZE microSD
// =====================================================

bool initSD()
{
    Serial.println();
    Serial.println("Initializing microSD...");


    pinMode(
        SD_CS,
        OUTPUT
    );

    digitalWrite(
        SD_CS,
        HIGH
    );


    sdSPI.begin(
        SD_SCK,
        SD_MISO,
        SD_MOSI,
        SD_CS
    );


    delay(100);


    if (!SD.begin(
            SD_CS,
            sdSPI,
            400000
        ))
    {
        Serial.println(
            "microSD INITIALIZATION FAILED"
        );

        return false;
    }


    Serial.println(
        "microSD INITIALIZED"
    );


    uint8_t cardType =
        SD.cardType();


    Serial.print(
        "Card type: "
    );


    if (cardType == CARD_MMC)
    {
        Serial.println("MMC");
    }
    else if (cardType == CARD_SD)
    {
        Serial.println("SDSC");
    }
    else if (cardType == CARD_SDHC)
    {
        Serial.println("SDHC");
    }
    else
    {
        Serial.println("UNKNOWN");
    }


    uint64_t cardSize =
        SD.cardSize() /
        (1024ULL * 1024ULL);


    Serial.print(
        "Card size: "
    );

    Serial.print(
        cardSize
    );

    Serial.println(
        " MB"
    );


    return true;
}


// =====================================================
//                  CREATE CSV
// =====================================================

void createCSV()
{
    if (!sdOK)
    {
        return;
    }


    if (!SD.exists("/g-one.csv"))
    {
        File file =
            SD.open(
                "/g-one.csv",
                FILE_WRITE
            );


        if (!file)
        {
            Serial.println(
                "Failed to create CSV"
            );

            return;
        }


        file.println(
            "timestamp,"
            "heart_rate,"
            "spo2,"
            "skin_temp,"
            "emg_adc,"
            "emg_voltage,"
            "acc_x,"
            "acc_y,"
            "acc_z,"
            "gyro_x,"
            "gyro_y,"
            "gyro_z"
        );


        file.close();


        Serial.println(
            "G-one CSV created"
        );
    }
}


// =====================================================
//                  SAVE TO SD
// =====================================================

void saveToSD()
{
    if (!sdOK)
    {
        return;
    }


    File file =
        SD.open(
            "/g-one.csv",
            FILE_APPEND
        );


    if (!file)
    {
        Serial.println(
            "SD WRITE ERROR"
        );

        return;
    }


    file.print(millis());
    file.print(",");


    file.print(
        (float)heartRate,
        2
    );

    file.print(",");


    file.print(
        (int)spo2
    );

    file.print(",");


    file.print(
        skinTemp,
        2
    );

    file.print(",");


    file.print(
        emgADC
    );

    file.print(",");


    file.print(
        emgVoltage,
        3
    );

    file.print(",");


    file.print(
        ax,
        3
    );

    file.print(",");


    file.print(
        ay,
        3
    );

    file.print(",");


    file.print(
        az,
        3
    );

    file.print(",");


    file.print(
        gx,
        2
    );

    file.print(",");


    file.print(
        gy,
        2
    );

    file.print(",");


    file.println(
        gz,
        2
    );


    file.close();
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
    // SD
    // -----------------------------------------

    Serial.print(
        "SD: "
    );

    Serial.println(
        sdOK
            ? "OK"
            : "NOT AVAILABLE"
    );


    Serial.println(
        "========================================"
    );
}


// =====================================================
//                         SETUP
// =====================================================

void setup()
{
    Serial.begin(115200);

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

    maxOK =
        initMAX30100();


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
    // microSD
    // =================================================

    sdOK =
        initSD();


    if (sdOK)
    {
        createCSV();
    }


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


    Serial.print(
        "microSD  : "
    );

    Serial.println(
        sdOK
            ? "READY"
            : "UNAVAILABLE"
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


    // =================================================
    // MPU6050
    // =================================================

    if (mpuOK)
    {
        readMPU();
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
    // SERIAL
    // =================================================

    if (millis() - lastPrint >=
        PRINT_INTERVAL)
    {
        lastPrint =
            millis();

        printSensorData();
    }


    // =================================================
    // SD
    // =================================================

    if (millis() - lastSDWrite >=
        SD_INTERVAL)
    {
        lastSDWrite =
            millis();

        saveToSD();
    }


    // Main loop can yield normally.
    // MAX30100 is independent.
    delay(2);
}
