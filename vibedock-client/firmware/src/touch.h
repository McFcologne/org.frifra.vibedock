// Touch panels of some boards (VIBEDOCK_TOUCH_AXS5106L, VIBEDOCK_TOUCH_CST816 with VIBEDOCK_TOUCH_SDA/SCL, optional
// VIBEDOCK_TOUCH_RST and VIBEDOCK_TOUCH_MIRROR_X). The controller is polled; its interrupt line is not used.
#pragma once
#if defined(VIBEDOCK_TOUCH_AXS5106L) || defined(VIBEDOCK_TOUCH_CST816)
#define VIBEDOCK_TOUCH 1
#include <Arduino.h>
#include <Wire.h>

namespace touch {
#if defined(VIBEDOCK_TOUCH_AXS5106L)
constexpr uint8_t address = 0x63, dataRegister = 0x01, dataLength = 6;  // [1] points, [2..5] x and y (12 bit)
constexpr uint8_t first = 2;
#else
constexpr uint8_t address = 0x15, dataRegister = 0x02, dataLength = 5;  // [0] points, [1..4] x and y (12 bit)
constexpr uint8_t first = 1;
#endif

inline void begin() {
#ifdef VIBEDOCK_TOUCH_RST
  pinMode(VIBEDOCK_TOUCH_RST, OUTPUT); digitalWrite(VIBEDOCK_TOUCH_RST, LOW); delay(20);
  digitalWrite(VIBEDOCK_TOUCH_RST, HIGH); delay(300);
#endif
  Wire.begin(VIBEDOCK_TOUCH_SDA, VIBEDOCK_TOUCH_SCL, 400000);
#if defined(VIBEDOCK_TOUCH_CST816)
  // Without this the CST816 sleeps after a few seconds and only answers while it is touched.
  Wire.beginTransmission(address); Wire.write(0xFE); Wire.write(0x01); Wire.endTransmission();
#endif
}
// The touched point in panel coordinates; false when nothing touches or the controller does not answer.
inline bool read(int16_t &x, int16_t &y) {
  uint8_t data[dataLength];
  Wire.beginTransmission(address); Wire.write(dataRegister);
  if (Wire.endTransmission() != 0 || Wire.requestFrom(address, dataLength) != dataLength) return false;
  for (auto &b : data) b = Wire.read();
  if ((data[first - 1] & 0x0F) == 0) return false;
  x = int16_t((data[first] & 0x0F) << 8 | data[first + 1]); y = int16_t((data[first + 2] & 0x0F) << 8 | data[first + 3]);
#ifdef VIBEDOCK_TOUCH_MIRROR_X
  x = int16_t(TFT_WIDTH - 1 - x);
#endif
  return true;
}
}
#endif
