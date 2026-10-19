// Battery sensing on the Waveshare ESP32-S3-Touch-LCD-1.54. Other boards do not touch any GPIO.
// Wiring: vendor schematic and bsp_power_manager.c at waveshareteam/ESP32-S3-Touch-LCD-1.54,
// revision 5157db7c888e476fd57f8a95020800377447478f. GPIO1: battery voltage / 3; GPIO3: active-low CHRG.
#pragma once
#include <Arduino.h>
#include "client_policy.h"

namespace battery {
struct Reading {
  bool supported = false;
  uint32_t millivolts = 0;
  int estimatedPercent = -1;
  const char *chargeSignal = "unknown";
};
inline void begin() {
#if defined(ESP32) && defined(VIBEDOCK_BATTERY_ADC) && defined(VIBEDOCK_BATTERY_CHARGE)
  pinMode(VIBEDOCK_BATTERY_CHARGE, INPUT_PULLUP);
  analogSetPinAttenuation(VIBEDOCK_BATTERY_ADC, ADC_11db);
#endif
}
inline Reading read() {
  Reading result;
#if defined(ESP32) && defined(VIBEDOCK_BATTERY_ADC) && defined(VIBEDOCK_BATTERY_CHARGE)
  result.supported = true;
  uint32_t total = 0; uint8_t active = 0;
  for (uint8_t i = 0; i < 16; ++i) {
    total += analogReadMilliVolts(VIBEDOCK_BATTERY_ADC);
    active += digitalRead(VIBEDOCK_BATTERY_CHARGE) == LOW;
    delay(1);
  }
  result.millivolts = (total * 3u + 8u) / 16u;
  result.estimatedPercent = client_policy::batteryEstimate(result.millivolts);
  result.chargeSignal = active == 16 ? "active" : active == 0 ? "inactive" : "unstable";
#endif
  return result;
}
}
