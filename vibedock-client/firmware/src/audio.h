#pragma once
#include "sound_policy.h"
namespace audio {
using Cue = sound_policy::Cue;
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
inline bool begin() { return false; }
inline bool available() { return false; }
inline void play(Cue) {}
inline uint8_t volume() { return 0; }
inline void setVolume(int) {}
inline bool takeVolumeChange() { return false; }
inline void showVolume() {}
inline bool takeVolumeInteraction() { return false; }
inline void quotas(const char*) {}
inline void connection(bool,uint32_t) {}
inline void pairingUsb(uint32_t) {}
#endif
}
