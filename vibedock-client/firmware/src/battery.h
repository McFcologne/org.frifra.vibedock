// Battery sensing: the voltage on VIBEDOCK_BATTERY_ADC (battery / 3) and, where wired, the charger's active-low CHRG on
// VIBEDOCK_BATTERY_CHARGE. Boards without them do not touch any GPIO.
// - Waveshare ESP32-S3-Touch-LCD-1.54: GPIO1 voltage, GPIO3 CHRG (vendor schematic and bsp_power_manager.c at
//   waveshareteam/ESP32-S3-Touch-LCD-1.54, revision 5157db7c888e476fd57f8a95020800377447478f).
// - Waveshare ESP32-C6-Touch-LCD-1.69: GPIO0 voltage (BAT_ADC, VBAT - 200k - BAT_ADC - 100k - GND in the vendor
//   schematic); the charger's STAT pin is not connected, so there is no charging signal.
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
#if defined(ESP32) && defined(VIBEDOCK_BATTERY_ADC)
  analogSetPinAttenuation(VIBEDOCK_BATTERY_ADC, ADC_11db);
#ifdef VIBEDOCK_BATTERY_CHARGE
  pinMode(VIBEDOCK_BATTERY_CHARGE, INPUT_PULLUP);
#endif
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
#if defined(ESP32) && defined(VIBEDOCK_BATTERY_ADC)
  result.supported = true;
  uint32_t total = 0; uint8_t active = 0;
  for (uint8_t i = 0; i < 16; ++i) {
    total += analogReadMilliVolts(VIBEDOCK_BATTERY_ADC);
#ifdef VIBEDOCK_BATTERY_CHARGE
    active += digitalRead(VIBEDOCK_BATTERY_CHARGE) == LOW;
#endif
    delay(1);
  }
  result.millivolts = (total * 3u + 8u) / 16u;
  result.estimatedPercent = client_policy::batteryEstimate(result.millivolts);
#ifdef VIBEDOCK_BATTERY_CHARGE
  result.chargeSignal = active == 16 ? "active" : active == 0 ? "inactive" : "unstable";
#endif
#endif
  return result;
}
}
