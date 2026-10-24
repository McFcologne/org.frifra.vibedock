#include <string>
#include <cstdio>
#include "../firmware/src/sound_policy.h"
#include "../firmware/src/volume_overlay.h"
#include "../firmware/src/client_policy.h"
#include <cassert>
#include <limits>
int main(){
  using namespace client_policy;
  {
    // Circles drawn with one finger, as the touch controller reports them every 20 ms.
    auto draw=[](TouchPath &p,float cx,float cy,float r,float startDeg,float sweepDeg,int points,float wobble=0){
      p.clear();
      for(int i=0;i<points;i++){ float a=(startDeg+sweepDeg*i/(points-1))*float(M_PI)/180; float rr=r+(i%2?wobble:-wobble);
        p.add(int16_t(cx+rr*cosf(a)),int16_t(cy+rr*sinf(a))); }
    };
    TouchPath p;
    draw(p,120,120,60,-90,350,50); assert(p.circle()==Circle::Clockwise);              // y points down: clockwise
    draw(p,120,120,60,-90,-350,50); assert(p.circle()==Circle::CounterClockwise);
    draw(p,100,140,50,30,320,40,6); assert(p.circle()==Circle::Clockwise);             // anywhere, a little uneven
    draw(p,120,120,60,-90,240,50); assert(p.circle()==Circle::None);                   // two thirds: no turn
    draw(p,120,120,20,-90,360,50); assert(p.circle()==Circle::None);                   // too small
    draw(p,120,120,60,-90,350,8); assert(p.circle()==Circle::None);                    // too few points
    draw(p,120,120,60,-90,350,200); assert(p.circle()==Circle::None);                  // slower than the points held
    p.clear(); for(int i=0;i<60;i++) p.add(int16_t(i%2?40:200),int16_t(120));          // back and forth, no circle
    assert(p.circle()==Circle::None);
    p.clear(); for(int i=0;i<40;i++) p.add(int16_t(40+4*i),int16_t(120)); assert(p.circle()==Circle::None);  // a swipe
    draw(p,120,120,60,-90,700,80); assert(p.circle()==Circle::Clockwise);              // two rounds: still one turn
  }
  {
    sound_policy::VolumeOverlay overlay; uint16_t row[240];
    for(int i=0;i<240;i++)row[i]=uint16_t(i);
    assert(!overlay.savedRow(0)); overlay.capture(95,row,240); assert(!overlay.savedRow(0));
    overlay.capture(96,row,240); assert(overlay.savedRow(0)[0]==20 && overlay.savedRow(0)[199]==219);
    overlay.show(100); assert(overlay.visible(1699)); assert(overlay.expired(1700));
    overlay.show(1600); assert(overlay.visible(2000)); // another press extends the notice
    row[20]=1234; overlay.capture(96,row,240); assert(overlay.savedRow(0)[0]==1234); // restore the NEW picture
    overlay.capture(96,row,200); assert(overlay.savedRow(0)[0]==1234); // reject incompatible rows
    overlay.dismiss(); assert(!overlay.visible(2000) && !overlay.expired(2000));
    overlay.show(0xfffffff0u); assert(overlay.visible(100)); assert(overlay.expired(1600));
    assert(!overlay.savedRow(-1) && !overlay.savedRow(48));
  }
  {
    using namespace sound_policy;
    assert(clampVolume(-1)==0 && clampVolume(101)==100 && clampVolume(30)==30);
    auto quiet=[](Changes c){return !c.up&&!c.down&&!c.full&&!c.empty;};
    // A provider reset can briefly omit the expired window before fresh readings arrive.
    Quotas rollover;
    assert(quiet(rollover.update("v1;0123456789abf:2000")));
    assert(quiet(rollover.update("v1")));
    assert(quiet(rollover.update("v1")));
    auto refilled=rollover.update("v1;0123456789abf:10000");
    assert(refilled.full && !refilled.up && !refilled.down && !refilled.empty);
    assert(quiet(rollover.update("v1;0123456789abf:10000")));
    Quotas q;
    assert(quiet(q.update("v1;0123456789abf:5000"))); // initial snapshot is silent
    assert(quiet(q.update("v1;0123456789abf:5000"))); // includes repeated HTTP 304 metadata
    auto c=q.update("v1;0123456789abf:4999"); assert(c.down&&!c.up); // even 0.01%, no threshold
    c=q.update("v1;0123456789abf:5000"); assert(c.up&&!c.full);
    c=q.update("v1;0123456789abf:10000"); assert(c.full&&!c.up);
    c=q.update("v1;0123456789abf:0"); assert(c.empty&&!c.down);
    assert(quiet(q.update("v1;0123456789abf:0")));
    assert(quiet(q.update("v1"))); // missing window preserves its last comparison value
    c=q.update("v1;0123456789abf:3000;0123456789abw:7000"); assert(c.up && !c.down);
    assert(quiet(q.update("v1;0123456789abw:7000;0123456789abf:3000"))); // order independent
    for(const char *bad:{"v2;0123456789abf:3000","v1;", "v1;a", "v1;0123456789abf:",
        "v1;0123456789abf:10001","v1;0123456789abf:-1","v1;0123456789abf:2x",
        "v1;0123456789abf:1;0123456789abf:2"}) assert(quiet(q.update(bad)));
    assert(quiet(q.update("v1;0123456789abf:3000;0123456789abw:7000"))); // malformed metadata did not advance state
    c=q.update("v1;0123456789abf:3001;0123456789abw:6999"); assert(c.up&&c.down);
    assert(quiet(q.update(""))); assert(quiet(q.update("v1;0123456789abf:10000"))); // old server / pairing reset
    // One window can expire while another stays present and changes.
    Quotas independent;
    assert(quiet(independent.update("v1;0123456789abf:2000;0123456789abw:9000")));
    c=independent.update("v1;0123456789abw:8999"); assert(c.down && !c.full);
    c=independent.update("v1;0123456789abf:10000;0123456789abw:8999"); assert(c.full && !c.down);
    // Retention is bounded: 20 current windows displace old absent ones; new windows still start silently.
    Quotas bounded;
    assert(quiet(bounded.update("v1;fffffffffffff:1000")));
    std::string many="v1";
    for(unsigned i=0;i<20;i++) { char value[32]; snprintf(value,sizeof(value),";%012xf:5000",i); many+=value; }
    assert(quiet(bounded.update(many.c_str()))); assert(quiet(bounded.update(many.c_str())));
    assert(quiet(bounded.update("v1;fffffffffffff:10000")));
    Connection state;
    assert(state.update(false,0)==0); assert(state.update(false,9000)==0);
    assert(state.update(true,10000)==1); assert(state.update(true,11000)==0);
    assert(state.update(false,12000)==0); assert(state.update(true,13000)==0); // short outage stays quiet
    assert(state.update(false,14000)==0); assert(state.update(false,18999)==0);
    assert(state.update(false,19000)==-1); assert(state.update(false,25000)==0);
    assert(state.update(true,26000)==1); assert(state.update(true,27000)==0);
    Connection wrap; assert(wrap.update(true,0xfffff000u)==1);
    assert(wrap.update(false,0xfffffff0u)==0); assert(wrap.update(false,5000)==-1);
  }
  // A disconnected/invalid ADC must never become a plausible percentage. Estimates are bounded and monotonic.
  assert(batteryEstimate(0) == -1); assert(batteryEstimate(2999) == -1);
  assert(batteryEstimate(4351) == -1); assert(batteryEstimate(0xffffffffu) == -1);
  assert(batteryEstimate(3300) == 1); assert(batteryEstimate(3700) == 40); assert(batteryEstimate(4200) == 100);
  for (uint32_t mv = 3000; mv <= 4350; ++mv) {
    assert(batteryEstimate(mv) >= 0 && batteryEstimate(mv) <= 100);
    if (mv > 3000) assert(batteryEstimate(mv) >= batteryEstimate(mv - 1));
  }
  const uint32_t beforeWrapPower=std::numeric_limits<uint32_t>::max()-1000;
  // Battery power saving: battery only without USB host and without charging; a change needs two readings in a row.
  assert(onBattery(false,false)); assert(!onBattery(true,false)); assert(!onBattery(false,true)); assert(!onBattery(true,true));
  {
    PowerSource source;
    assert(!source.update(false)); assert(!source.battery);
    assert(!source.update(true)); assert(!source.battery);       // one reading is not enough
    assert(!source.update(false)); assert(!source.update(true));  // flickering never switches
    assert(source.update(true)); assert(source.battery);
    assert(!source.update(true)); assert(!source.update(false)); assert(source.update(false)); assert(!source.battery);
  }
  assert(!dimDue(59999,0)); assert(dimDue(60000,0)); assert(!dimDue(5000,beforeWrapPower)); assert(dimDue(59000,beforeWrapPower));
  assert(dimmedBrightness(100)==minBrightness); assert(dimmedBrightness(minBrightness)==minBrightness);
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
  ToneSettings m1;m1.gamma=250;m1.red=91;m1.green=94;m1.blue=73;Tone tone(m1);assert(tone.apply(0)==0);assert(tone.apply(0xf800)==28<<11);
  assert(((tone.apply(0x07e0)>>5)&63)==59);assert((tone.apply(0x001f)&31)==23);
  assert(((tone.apply(0x0400)>>5)&63)<16);  // gamma 2.5 lowers the mid tones
  assert(swipeOf(-60,10)==Swipe::Left);assert(swipeOf(60,-20)==Swipe::Right);assert(swipeOf(5,-50)==Swipe::Up);assert(swipeOf(0,80)==Swipe::Down);
  assert(swipeOf(25,0)==Swipe::None);assert(swipeOf(50,40)==Swipe::None);assert(swipeOf(-45,28)==Swipe::Left);assert(swipeOf(30,0)==Swipe::Right);assert(swipeOf(0,0)==Swipe::None);
  assert(nextBrightness(100,true)==100);assert(nextBrightness(100,false)==85);assert(nextBrightness(20,false)==10);assert(nextBrightness(10,true)==25);
  // Orientation: upright and flipped on every panel, the quarter turns only on square ones; unknown keeps the turn.
  assert(turnsOf("flipped",0,false)==2);assert(turnsOf("upright",3,true)==0);assert(turnsOf("",1,true)==1);assert(turnsOf("x",2,false)==2);
  assert(turnsOf("rotated-90",0,true)==1);assert(turnsOf("rotated-270",0,true)==3);assert(turnsOf("rotated-90",2,false)==2);
  assert(turnsOf("rotated-9",2,true)==2);assert(turnsOf("flipped-x",1,true)==1);
  // Swipes turn with the picture: a quarter turn twice is the flip (both directions negated).
  { int dx=10,dy=-3; turnSwipe(dx,dy,0); assert(dx==10&&dy==-3); turnSwipe(dx,dy,1); assert(dx==-3&&dy==-10);
    dx=10;dy=-3; turnSwipe(dx,dy,2); assert(dx==-10&&dy==3); dx=10;dy=-3; turnSwipe(dx,dy,3); assert(dx==3&&dy==10);
    dx=10;dy=-3; turnSwipe(dx,dy,4); assert(dx==10&&dy==-3); }
  assert(isTap(3,-4,120));assert(!isTap(20,0,100));assert(!isTap(0,0,500));
  assert(doubleTap(1000,1300));assert(!doubleTap(1000,1500));assert(!doubleTap(0,100));
  assert(clampBrightness(0)==10);assert(clampBrightness(255)==100);
  assert(!legacyCalibration(250,91,94,73));assert(legacyCalibration(240,91,94,73));assert(ToneSettings()==ToneSettings());
  // Starts: planned restarts are named by their intent, only software restarts take one.
  assert(std::string(startKind(ResetKind::Software,Intent::Update))=="update");assert(std::string(startKind(ResetKind::Software,Intent::None))=="software");
  assert(std::string(startKind(ResetKind::Panic,Intent::Update))=="crash");assert(std::string(startKind(ResetKind::PowerOn,Intent::Setup))=="power-on");
  assert(std::string(startKind(ResetKind::Watchdog,Intent::None))=="watchdog");assert(std::string(startKind(ResetKind::External,Intent::None))=="external");
  assert(std::string(phaseName(uint8_t(Phase::Ota)))=="ota");assert(phaseName(0)==nullptr);assert(phaseName(12)==nullptr);
  // Diagnosis records: valid only with magic and check; any changed byte (memory after a power cut) is unknown.
  DiagRecord r{diagMagic,0x1a2b3c4d,100,20000,15000,-60,uint8_t(Phase::Image),0,1,0};r.check=diagCheck(r);assert(diagValid(r));
  DiagRecord garbage=r;garbage.uptime^=1;assert(!diagValid(garbage));
  DiagRecord zero{};assert(!diagValid(zero));
  DiagRecord badPhase=r;badPhase.phase=99;badPhase.check=diagCheck(badPhase);assert(!diagValid(badPhase));
  static_assert(sizeof(DiagRecord)%4==0,"RTC memory is written in 4-byte words");
  assert(clampGamma(10)==50);assert(clampGamma(999)==350);assert(clampGain(0)==40);assert(clampGain(200)==100);
}
