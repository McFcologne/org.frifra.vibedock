// Battery sensing on the Waveshare ESP32-S3-Touch-LCD-1.54. Other boards do not touch any GPIO.
// Wiring: vendor schematic and bsp_power_manager.c at waveshareteam/ESP32-S3-Touch-LCD-1.54,
// revision 5157db7c888e476fd57f8a95020800377447478f. GPIO1: battery voltage / 3; GPIO3: active-low CHRG.
#pragma once
#include <Arduino.h>
#include "client_policy.h"
// Boards with a battery connection (VIBEDOCK_BATTERY) on chips with their own USB serial/JTAG unit.
#if defined(ESP32) && defined(VIBEDOCK_BATTERY) && (defined(CONFIG_IDF_TARGET_ESP32S3) || defined(CONFIG_IDF_TARGET_ESP32C3) || defined(CONFIG_IDF_TARGET_ESP32C6))
#define VIBEDOCK_USB_HOST_SENSE 1
#include "soc/usb_serial_jtag_reg.h"
#endif

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
// A USB host is attached: it sends a start-of-frame every millisecond, which the chip's USB serial/JTAG unit
// counts (ESP32-S3, -C3, -C6). A charger or power bank without data lines sends none. Elsewhere always true.
inline bool usbHost() {
#ifdef VIBEDOCK_USB_HOST_SENSE
  uint32_t first = REG_READ(USB_SERIAL_JTAG_FRAM_NUM_REG) & USB_SERIAL_JTAG_SOF_FRAME_INDEX;
  delay(3);
  return (REG_READ(USB_SERIAL_JTAG_FRAM_NUM_REG) & USB_SERIAL_JTAG_SOF_FRAME_INDEX) != first;
#else
  return true;
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
