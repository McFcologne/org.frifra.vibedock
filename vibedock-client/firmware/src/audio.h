#pragma once
#include <stdint.h>
namespace audio {
enum class Cue : uint8_t { Boot, Connected, PairWifi, PairUsb, Up, Down, Lost, Full, Empty, Louder, Quieter, Swipe };
#ifdef VIBEDOCK_AUDIO_ES8311
bool begin();
bool available();
void play(Cue cue);
uint8_t volume();
void setVolume(int value);
bool takeVolumeChange();
void showVolume();
bool takeVolumeInteraction();
void quotas(const char *header);
void connection(bool healthy, uint32_t now);
void pairingUsb(uint32_t now);
#else
// Keep even the empty stubs out of non-audio firmware with Core 3 / GCC 14.

inline __attribute__((always_inline)) bool begin() { return false; }
inline __attribute__((always_inline)) bool available() { return false; }
inline __attribute__((always_inline)) void play(Cue) {}
inline __attribute__((always_inline)) uint8_t volume() { return 0; }
inline __attribute__((always_inline)) void setVolume(int) {}
inline __attribute__((always_inline)) bool takeVolumeChange() { return false; }
inline __attribute__((always_inline)) void showVolume() {}
inline __attribute__((always_inline)) bool takeVolumeInteraction() { return false; }
inline __attribute__((always_inline)) void quotas(const char*) {}
inline __attribute__((always_inline)) void connection(bool,uint32_t) {}
inline __attribute__((always_inline)) void pairingUsb(uint32_t) {}
#endif
}
