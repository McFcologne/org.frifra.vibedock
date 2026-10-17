#include "../firmware/src/client_policy.h"
#include <cassert>
#include <limits>
int main(){
  using namespace client_policy;
  assert(clampPoll(1)==10);assert(clampPoll(30)==30);assert(clampPoll(99999)==3600);
  assert(!pullDue(29999,0,30));assert(pullDue(30000,0,30));assert(pullDue(10000,0,5));
  assert(!imageStale(89999,0,30));assert(imageStale(90000,0,30));
  const uint32_t beforeWrap=std::numeric_limits<uint32_t>::max()-1000;
  assert(!pullDue(5000,beforeWrap,30));assert(pullDue(29000,beforeWrap,30));
  assert(!imageStale(5000,beforeWrap,30));
  assert(nextServer(0,2)==1);assert(nextServer(1,2)==0);assert(nextServer(0,1)==0);assert(nextServer(0,0)==0);
  assert(pullRequestAllowed(5000,0));assert(!pullRequestAllowed(4999,0));assert(!pullRequestAllowed(1000,beforeWrap));assert(pullRequestAllowed(5000,beforeWrap));
  assert(!pairingLocked(4));assert(pairingLocked(5));
  assert(countsAsPowerCycle(0));assert(countsAsPowerCycle(6));for(uint32_t r=1;r<=5;r++)assert(!countsAsPowerCycle(r));
  assert(!factoryResetDue(0));assert(!factoryResetDue(2));assert(factoryResetDue(3));assert(factoryResetDue(255));
  uint8_t row[480]={};const uint32_t blank=rowHash(row,sizeof(row));assert(blank!=0);
  row[479]=1;assert(rowHash(row,sizeof(row))!=blank);row[479]=0;assert(rowHash(row,sizeof(row))==blank);
  assert(rowHash(row,0)!=0);
  assert(!lightColor(0x0000));assert(lightColor(0xffff));assert(lightColor(0xf79e));assert(!lightColor(0x18e3));assert(!lightColor(0x001f));
  uint8_t fp[20];assert(parseFingerprint("00112233445566778899aabbccddeeff01234567",fp));assert(fp[0]==0x00&&fp[1]==0x11&&fp[19]==0x67);
  assert(!parseFingerprint("00112233445566778899AABBCCDDEEFF01234567",fp));assert(!parseFingerprint("0011",fp));
  assert(!parseFingerprint("00112233445566778899aabbccddeeff012345678",fp));
  assert(validLogRequest("0123456789abcdef0123456789abcdef"));assert(!validLogRequest(""));
  assert(!validLogRequest("0123456789ABCDEF0123456789abcdef"));assert(!validLogRequest("0123456789abcdef0123456789abcdef0"));
  assert(!validLogRequest("0123456789abcdef0123456789abcde"));assert(!validLogRequest("0123456789abcdef0123456789abcd\n0"));
  assert(pixel(0xf8,0)==0xf800);assert(pixel(0x07,0xe0)==0x07e0);assert(pixel(0,0x1f)==0x001f);
  ToneSettings neutral;neutral.gamma=100;neutral.red=neutral.green=neutral.blue=100;Tone same(neutral);
  for(uint32_t c=0;c<65536;c++)assert(same.apply(uint16_t(c))==c);
  Tone tone;assert(tone.apply(0)==0);assert(tone.apply(0xf800)==28<<11);
  assert(((tone.apply(0x07e0)>>5)&63)==59);assert((tone.apply(0x001f)&31)==23);
  assert(((tone.apply(0x0400)>>5)&63)<16);  // gamma 2.5 lowers the mid tones
  assert(swipeOf(-60,10)==Swipe::Left);assert(swipeOf(60,-20)==Swipe::Right);assert(swipeOf(5,-50)==Swipe::Up);assert(swipeOf(0,80)==Swipe::Down);
  assert(swipeOf(25,0)==Swipe::None);assert(swipeOf(50,40)==Swipe::None);assert(swipeOf(-45,28)==Swipe::Left);assert(swipeOf(30,0)==Swipe::Right);assert(swipeOf(0,0)==Swipe::None);
  assert(nextBrightness(100,true)==100);assert(nextBrightness(100,false)==85);assert(nextBrightness(20,false)==10);assert(nextBrightness(10,true)==25);
  assert(flippedOf("flipped",false));assert(!flippedOf("upright",true));assert(flippedOf("",true));assert(!flippedOf("",false));assert(!flippedOf("x",false));
  assert(clampBrightness(0)==10);assert(clampBrightness(255)==100);
  assert(!legacyCalibration(250,91,94,73));assert(legacyCalibration(240,91,94,73));assert(ToneSettings()==ToneSettings());
  assert(clampGamma(10)==50);assert(clampGamma(999)==350);assert(clampGain(0)==40);assert(clampGain(200)==100);
}
