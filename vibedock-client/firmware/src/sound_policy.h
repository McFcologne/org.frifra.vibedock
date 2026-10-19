#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
namespace sound_policy {
enum class Cue : uint8_t { Boot, Connected, PairWifi, PairUsb, Up, Down, Lost, Full, Empty, Louder, Quieter, Swipe };
constexpr uint8_t clampVolume(int value) { return value < 0 ? 0 : value > 100 ? 100 : uint8_t(value); }
struct Changes { bool up=false, down=false, full=false, empty=false; };
// v1;<13-character window id>:<remaining basis points>;... (0..10000, at most 20 windows).
// Validate the complete header before touching the baseline. Missing/new windows establish silent baselines.
class Quotas {
  struct Value { char id[14] = {}; uint16_t remaining = 0; };
  Value previous[20]; uint8_t count = 0;
 public:
  Changes update(const char *text) {
    Changes changes;
    if (!text || !*text) { count=0; return changes; } // old server: forget previous server's state
    if (strlen(text)>420 || strncmp(text,"v1",2)!=0 || (text[2] && text[2]!=';')) return changes;
    Value next[20]; uint8_t size=0; const char *p=text+2;
    while (*p) {
      if (*p++!=';' || size==20) return Changes{};
      for (uint8_t i=0;i<13;i++) {
        char c=*p++; if (!((c>='0'&&c<='9')||(c>='a'&&c<='z'))) return Changes{};
        next[size].id[i]=c;
      }
      if (*p++!=':') return Changes{};
      unsigned value=0; uint8_t digits=0;
      while (*p>='0'&&*p<='9') { if (++digits>5) return Changes{}; value=value*10+unsigned(*p++-'0'); }
      if (!digits || value>10000 || (*p && *p!=';')) return Changes{};
      for(uint8_t i=0;i<size;i++) if(strcmp(next[i].id,next[size].id)==0) return Changes{};
      next[size++].remaining=uint16_t(value);
    }
    for(uint8_t i=0;i<size;i++) for(uint8_t j=0;j<count;j++) if(strcmp(next[i].id,previous[j].id)==0) {
      if(next[i].remaining>previous[j].remaining) {
        if(next[i].remaining==10000) changes.full=true; else changes.up=true;
      } else if(next[i].remaining<previous[j].remaining) {
        if(next[i].remaining==0) changes.empty=true; else changes.down=true;
      }
      break;
    }
    memcpy(previous,next,sizeof(next)); count=size; return changes;
  }
};
// One lost cue after a sustained outage, one connected cue when valid replies resume. No alert before first contact.
class Connection {
  bool seen=false, connected=false, waiting=false; uint32_t since=0;
 public:
  int update(bool healthy,uint32_t now) {
    if(healthy) { waiting=false; if(!connected) { seen=connected=true; return 1; } }
    else if(seen) {
      if(!waiting) { waiting=true; since=now; }
      if(connected && uint32_t(now-since)>=5000) { connected=false; return -1; }
    }
    return 0;
  }
};
}
