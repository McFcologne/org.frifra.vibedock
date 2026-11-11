// Buttons and joystick of some boards: every VIBEDOCK_KEY_<A|B|X|Y|UP|DOWN|LEFT|RIGHT|PRESS> that an environment defines
// is a pin that reads low while the key is pressed (internal pull-up). Boards without any key compile none of this.
#pragma once
#if defined(VIBEDOCK_KEY_A) || defined(VIBEDOCK_KEY_B) || defined(VIBEDOCK_KEY_X) || defined(VIBEDOCK_KEY_Y) || \
    defined(VIBEDOCK_KEY_UP) || defined(VIBEDOCK_KEY_DOWN) || defined(VIBEDOCK_KEY_LEFT) || defined(VIBEDOCK_KEY_RIGHT) || \
    defined(VIBEDOCK_KEY_PRESS)
#define VIBEDOCK_KEYS 1
#include <Arduino.h>
#include "client_policy.h"

namespace keys {
struct Pin { int8_t pin; client_policy::PadKey key; };
constexpr Pin pins[] = {
#ifdef VIBEDOCK_KEY_A
  {VIBEDOCK_KEY_A, client_policy::PadKey::A},
#endif
#ifdef VIBEDOCK_KEY_B
  {VIBEDOCK_KEY_B, client_policy::PadKey::B},
#endif
#ifdef VIBEDOCK_KEY_X
  {VIBEDOCK_KEY_X, client_policy::PadKey::X},
#endif
#ifdef VIBEDOCK_KEY_Y
  {VIBEDOCK_KEY_Y, client_policy::PadKey::Y},
#endif
#ifdef VIBEDOCK_KEY_UP
  {VIBEDOCK_KEY_UP, client_policy::PadKey::Up},
#endif
#ifdef VIBEDOCK_KEY_DOWN
  {VIBEDOCK_KEY_DOWN, client_policy::PadKey::Down},
#endif
#ifdef VIBEDOCK_KEY_LEFT
  {VIBEDOCK_KEY_LEFT, client_policy::PadKey::Left},
#endif
#ifdef VIBEDOCK_KEY_RIGHT
  {VIBEDOCK_KEY_RIGHT, client_policy::PadKey::Right},
#endif
#ifdef VIBEDOCK_KEY_PRESS
  {VIBEDOCK_KEY_PRESS, client_policy::PadKey::Press},
#endif
};
constexpr size_t count = sizeof(pins) / sizeof(pins[0]);
inline void begin() { for (const auto &k : pins) pinMode(k.pin, INPUT_PULLUP); }
// The key pressed since the last call (the first one if several), PadKey::Count when none.
inline client_policy::PadKey poll(uint32_t now) {
  static client_policy::PadDebounce state[count];
  auto found = client_policy::PadKey::Count;
  for (size_t i = 0; i < count; i++)
    if (state[i].pressed(digitalRead(pins[i].pin) == LOW, now) && found == client_policy::PadKey::Count) found = pins[i].key;
  return found;
}
}
#endif
