/*
 * G-one wire tracer — ESP32-S3
 *
 * For when the wiring looks right but a pin reads as though nothing is on it. It answers one
 * question: WHICH PIN is this wire actually in?
 *
 * How to use it:
 *   1. Flash it and open the Serial Monitor at 115200. It prints which pins already sit high
 *      or low by themselves — that alone is a map of what is connected where.
 *   2. Unplug the wire you are chasing AT THE SENSOR END, so only the ESP32 end is still in.
 *   3. Touch its free end to the 3V3 rail. The pin it is plugged into is named within a second.
 *      Touch it to GND instead and it is named the other way round.
 *   4. Nothing named? The wire is broken, or its ESP32 end is not making contact. Dupont
 *      jumpers fail open often enough that this is the usual answer.
 *
 * Every pin here is only ever an input, so touching a wire to 3V3 or GND cannot short anything.
 *
 * It skips the pins that are not yours to touch on an N16R8: 19 and 20 (USB), 35-37 (the octal
 * PSRAM), 43 and 44 (the serial port), and the strapping pins 0, 3, 45 and 46.
 */

#include <Arduino.h>

struct Pin { int gpio; const char* use; };

// The pins a G-one build can reach, with what the wearable sketch expects on each.
static const Pin PINS[] = {
  {1,  "EMG"},      {2,  ""},         {4,  "DS18B20"},  {5,  ""},         {6,  ""},
  {7,  ""},         {8,  "I2C SDA"},  {9,  "I2C SCL"},  {10, "SD CS"},    {11, "SD MOSI"},
  {12, "SD SCK"},   {13, "SD MISO"},  {14, ""},         {15, ""},         {16, ""},
  {17, ""},         {18, ""},         {21, ""},         {38, ""},         {39, ""},
  {40, ""},         {41, ""},         {42, ""},         {47, ""},         {48, ""},
};
static const size_t PIN_COUNT = sizeof(PINS) / sizeof(PINS[0]);

bool heldHigh[PIN_COUNT];   // something outside holds it up, against the chip's pull-down
bool heldLow[PIN_COUNT];    // something outside holds it down, against the chip's pull-up
bool firstPass = true;

void readAll(bool* high, bool* low) {
  for (size_t i = 0; i < PIN_COUNT; i++) {
    pinMode(PINS[i].gpio, INPUT_PULLDOWN);
    delayMicroseconds(200);
    high[i] = digitalRead(PINS[i].gpio) == HIGH;

    pinMode(PINS[i].gpio, INPUT_PULLUP);
    delayMicroseconds(200);
    low[i] = digitalRead(PINS[i].gpio) == LOW;

    pinMode(PINS[i].gpio, INPUT);
  }
}

void printSet(const char* label, const bool* set) {
  Serial.print(label);
  int found = 0;
  for (size_t i = 0; i < PIN_COUNT; i++) {
    if (!set[i]) continue;
    found++;
    Serial.printf(" GPIO %d", PINS[i].gpio);
    if (PINS[i].use[0] != '\0') Serial.printf(" (%s)", PINS[i].use);
  }
  Serial.println(found == 0 ? " none" : "");
}

void setup() {
  Serial.begin(115200);
  uint32_t waitStart = millis();
  while (!Serial && millis() - waitStart < 2000) delay(10);

  Serial.println();
  Serial.println("========== G-one wire tracer ==========");
  Serial.println("Unplug a wire at the SENSOR end, then touch its free end to the 3V3 rail.");
  Serial.println("The pin it is really in will be named below. Touch it to GND for the other way.");
  Serial.println();
}

void loop() {
  bool high[PIN_COUNT];
  bool low[PIN_COUNT];
  readAll(high, low);

  bool changed = firstPass;
  for (size_t i = 0; i < PIN_COUNT && !changed; i++) {
    if (high[i] != heldHigh[i] || low[i] != heldLow[i]) changed = true;
  }

  if (changed) {
    if (firstPass) Serial.println("As it stands now:");
    printSet("  held HIGH from outside:", high);
    printSet("  held LOW  from outside:", low);
    Serial.println();
    memcpy(heldHigh, high, sizeof(heldHigh));
    memcpy(heldLow, low, sizeof(heldLow));
    firstPass = false;
  }

  delay(200);
}
