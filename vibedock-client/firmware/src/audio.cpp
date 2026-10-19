// Original fixed-format playback, no audio-file decoder or microphone capture.
// Pins and ES8311 register settings: Waveshare ESP32-S3-Touch-LCD-1.54 example 01_i2s_audio
// at revision 5157db7c888e476fd57f8a95020800377447478f (16 kHz, MCLK 256 x sample rate).
#include "audio.h"
#ifdef VIBEDOCK_AUDIO_ES8311
#include <Arduino.h>
#include <Wire.h>
#include <driver/i2s.h>
#include <atomic>
#include <math.h>
namespace audio {
namespace {
constexpr uint32_t rate=16000;
// Physical case labels: + is GPIO4, - is GPIO0. GPIO5 is PWR and is not a volume button.
constexpr uint8_t amplifier=7, plus=4, minus=0;
std::atomic<uint8_t> level{30}; std::atomic<bool> changed{false}, interaction{false};
QueueHandle_t queue=nullptr;
sound_policy::Quotas quotaState; sound_policy::Connection connectionState;
struct Note { float start, end; uint16_t ms, gap; float gain; };
constexpr Note boot[]={{261.63,261.63,110,30,.26},{392,392,110,30,.26},{523.25,523.25,250,0,.26}};
constexpr Note connected[]={{783.99,783.99,110,45,.26},{1046.5,1046.5,230,0,.26}};
constexpr Note pairWifi[]={{523.25,523.25,130,60,.26},{659.25,659.25,200,240,.26},{523.25,523.25,130,60,.26},{659.25,659.25,200,0,.26}};
constexpr Note pairUsb[]={{523.25,523.25,90,45,.26},{523.25,523.25,90,45,.26},{783.99,783.99,220,240,.26},{523.25,523.25,90,45,.26},{523.25,523.25,90,45,.26},{783.99,783.99,220,0,.26}};
constexpr Note up[]={{523.25,523.25,120,25,.28},{659.25,659.25,100,25,.28},{783.99,783.99,100,25,.28},{1046.5,1046.5,300,25,.28}};
constexpr Note down[]={{261.63,196,400,0,.30}};
constexpr Note lost[]={{440,338.8,145,18,.28},{392,301.84,145,18,.255},{349.23,268.9071,145,18,.23},{293.66,226.1182,145,18,.205},{220,164.81,250,0,.20}};
constexpr Note louderSound[]={{880,1046.5,80,0,.22}};
constexpr Note quieterSound[]={{329.63,220,120,0,.25}};
constexpr Note swipeSound[]={{1400,450,90,0,.12}};
struct Melody { const Note *notes; size_t count; };
template<size_t N> Melody melody(const Note (&notes)[N]) { return {notes,N}; }
Melody melody(Cue cue) {
  switch(cue) {
    case Cue::Boot:return melody(boot); case Cue::Connected:return melody(connected);
    case Cue::PairWifi:return melody(pairWifi); case Cue::PairUsb:return melody(pairUsb);
    case Cue::Up:case Cue::Full:return melody(up); case Cue::Down:case Cue::Empty:return melody(down);
    case Cue::Louder:return melody(louderSound); case Cue::Quieter:return melody(quieterSound);
    case Cue::Swipe:return melody(swipeSound); default:return melody(lost);
  }
}
struct Button {
  explicit Button(uint8_t value):pin(value) {}
  uint8_t pin; bool raw=false, stable=false; uint32_t at=0;
  bool poll(uint32_t now) {
    bool pressed=digitalRead(pin)==LOW;
    if(pressed!=raw) { raw=pressed; at=now; }
    if(stable!=raw && uint32_t(now-at)>=35) { stable=raw; return stable; }
    return false;
  }
};
Button louder{plus}, quieter{minus};
void buttons() {
  auto now=millis();
  if(louder.poll(now)) {
    showVolume();
    auto before=volume(); setVolume(int(before)+10);
    if(volume()!=before) play(Cue::Louder);
  }
  if(quieter.poll(now)) {
    showVolume();
    auto before=volume(); setVolume(int(before)-10);
    if(volume()!=before) play(Cue::Quieter);
  }
}
bool send(const int16_t *data,size_t bytes) {
  size_t offset=0; auto began=millis();
  while(offset<bytes) {
    size_t done=0;
    if(i2s_write(I2S_NUM_0,reinterpret_cast<const uint8_t*>(data)+offset,bytes-offset,&done,pdMS_TO_TICKS(30))!=ESP_OK) return false;
    offset+=done; buttons();
    if(uint32_t(millis()-began)>1000) return false;
  }
  return true;
}
void player(void*) {
  int16_t data[256*2]={}; Cue cue;
  for(;;) {
    buttons();
    if(xQueueReceive(queue,&cue,pdMS_TO_TICKS(10))!=pdTRUE) continue;
    if(volume()==0) continue;
    auto tune=melody(cue); digitalWrite(amplifier,HIGH);
    bool ok=true;
    for(size_t n=0;n<tune.count && ok;n++) {
      const auto &note=tune.notes[n]; uint32_t samples=rate*note.ms/1000, gap=rate*note.gap/1000;
      for(uint32_t offset=0;offset<samples+gap && ok;offset+=256) {
        uint32_t length=std::min(uint32_t(256),samples+gap-offset);
        float gain=volume()/100.0f;
        for(uint32_t j=0;j<length;j++) {
          uint32_t i=offset+j; int16_t sample=0;
          if(i<samples) {
            float t=float(i)/rate, duration=note.ms/1000.0f;
            float phase=2.0f*PI*(note.start*t+(note.end-note.start)*t*t/(2*duration));
            float envelope=std::max(0.0f,std::min(1.0f,std::min(t/.009f,(duration-t)/.035f)));
            sample=int16_t(32767*gain*note.gain*envelope*(sinf(phase)+.16f*sinf(2*phase))/1.16f);
          }
          data[2*j]=data[2*j+1]=sample;
        }
        ok=send(data,length*2*sizeof(int16_t));
      }
    }
    // Drain the DMA tail before muting the external amplifier; silence also clears old samples.
    memset(data,0,sizeof(data)); for(int i=0;i<5 && ok;i++) ok=send(data,sizeof(data));
    digitalWrite(amplifier,LOW); i2s_zero_dma_buffer(I2S_NUM_0);
  }
}
bool reg(uint8_t address,uint8_t value) {
  Wire.beginTransmission(0x18); Wire.write(address); Wire.write(value); return Wire.endTransmission()==0;
}
}
bool begin() {
  pinMode(amplifier,OUTPUT); digitalWrite(amplifier,LOW);
  pinMode(plus,INPUT_PULLUP); pinMode(minus,INPUT_PULLUP);
  // Wire already belongs to the touch controller on SDA42/SCL41. Only this startup code uses it for audio.
  i2s_config_t config={}; config.mode=i2s_mode_t(I2S_MODE_MASTER|I2S_MODE_TX);
  config.sample_rate=rate; config.bits_per_sample=I2S_BITS_PER_SAMPLE_16BIT;
  config.channel_format=I2S_CHANNEL_FMT_RIGHT_LEFT; config.communication_format=I2S_COMM_FORMAT_STAND_I2S;
  config.intr_alloc_flags=ESP_INTR_FLAG_LEVEL1; config.dma_buf_count=4; config.dma_buf_len=256;
  config.tx_desc_auto_clear=true; config.fixed_mclk=rate*256; config.mclk_multiple=I2S_MCLK_MULTIPLE_256;
  if(i2s_driver_install(I2S_NUM_0,&config,0,nullptr)!=ESP_OK) return false;
  i2s_pin_config_t pins={}; pins.mck_io_num=8; pins.bck_io_num=9; pins.ws_io_num=10;
  pins.data_out_num=12; pins.data_in_num=I2S_PIN_NO_CHANGE;
  bool ok=i2s_set_pin(I2S_NUM_0,&pins)==ESP_OK;
  if(ok) { ok=reg(0x00,0x1f); delay(20); }
  // ES8311 slave, I2S 16-bit, 4.096 MHz MCLK, DAC output enabled; no microphone input is read.
  const uint8_t registers[][2]={{0x00,0x00},{0x00,0x80},{0x01,0x3f},{0x02,0x00},{0x03,0x10},{0x04,0x10},
    {0x05,0x00},{0x06,0x03},{0x07,0x00},{0x08,0xff},{0x00,0x80},{0x09,0x0c},{0x0a,0x0c},
    {0x0d,0x01},{0x0e,0x02},{0x12,0x00},{0x13,0x10},{0x1c,0x6a},{0x37,0x08},{0x32,0xbf},{0x31,0x00}};
  for(const auto &entry:registers) if(ok) ok=reg(entry[0],entry[1]);
  if(ok) queue=xQueueCreate(8,sizeof(Cue));
  if(!queue || xTaskCreate(player,"vibedock-audio",4096,nullptr,1,nullptr)!=pdPASS) {
    if(queue) { vQueueDelete(queue); queue=nullptr; }
    i2s_driver_uninstall(I2S_NUM_0); return false;
  }
  return true;
}
bool available() { return queue!=nullptr; }
uint8_t volume() { return level.load(); }
void setVolume(int value) {
  uint8_t next=sound_policy::clampVolume(value);
  if(level.exchange(next)!=next) { changed=true; showVolume(); }
}
bool takeVolumeChange() { return changed.exchange(false); }
void showVolume() { interaction=true; }
bool takeVolumeInteraction() { return interaction.exchange(false); }
void play(Cue cue) { if(queue && volume()) xQueueSend(queue,&cue,0); } // drop overflow rather than block image reception
void quotas(const char *header) {
  auto changes=quotaState.update(header);
  if(changes.full) play(Cue::Full); if(changes.empty) play(Cue::Empty);
  if(changes.up) play(Cue::Up); if(changes.down) play(Cue::Down);
}
void connection(bool healthy,uint32_t now) {
  int event=connectionState.update(healthy,now);
  if(event==1) play(Cue::Connected); else if(event==-1) play(Cue::Lost);
}
void pairingUsb(uint32_t now) {
  static bool prompted=false; static uint32_t last=0;
  if(!prompted || uint32_t(now-last)>=30000) { prompted=true; last=now; play(Cue::PairUsb); }
}
}
#endif
