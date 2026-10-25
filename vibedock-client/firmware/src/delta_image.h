#pragma once
#include "client_policy.h"

namespace client_policy {
inline uint32_t hashByte(uint32_t h, uint8_t b) { return (h ^ b) * 16777619u; }
inline uint32_t rowsHash(const uint32_t *rows, uint16_t height) {
  uint32_t h=2166136261u;
  for(uint16_t y=0;y<height;y++) for(int shift=24;shift>=0;shift-=8) h=hashByte(h,uint8_t(rows[y]>>shift));
  return h;
}
inline bool parseHash(const char *text,uint32_t &out) {
  uint32_t h=0;
  for(int i=0;i<8;i++) {
    char c=text[i]; if(!((c>='0'&&c<='9')||(c>='a'&&c<='f'))) return false;
    h=(h<<4)|uint32_t(c<='9'?c-'0':c-'a'+10);
  }
  if(text[8])return false;
  out=h;return true;
}
inline bool deltaLength(const char *text,uint32_t limit,uint32_t &out) {
  uint32_t n=0;if(!*text)return false;
  for(;*text;text++){
    if(*text<'0'||*text>'9'||n>limit/10)return false;
    n=n*10+uint32_t(*text-'0');if(n>=limit)return false;
  }
  if(n<2)return false;
  out=n;return true;
}
// Streaming decoder. read fills exactly n bytes or fails; draw receives one uncorrected RGB565 span.
// The caller owns a 2*maxImageSide scratch buffer. After a failure the rows of the broken section that were not
// drawn have hash 0; the caller trusts that only when the transfer was cut off, otherwise it forgets all rows.
// Sections are disjoint, ascending groups of rows, at most height groups. No framebuffer or allocation.
template<typename Read,typename Draw>
bool readDelta(ImageSize size,uint32_t length,uint32_t expectedPayload,uint32_t expectedRows,
               uint32_t *rows,uint8_t *scratch,Read read,Draw draw) {
  if(size.width<16||size.height<16||size.width>maxImageSide||size.height>maxImageSide||length<2||length>=size.bytes())return false;
  uint32_t remaining=length,hash=2166136261u;
  auto take=[&](uint8_t *data,size_t n){
    if(n>remaining||!read(data,n))return false;
    for(size_t i=0;i<n;i++)hash=hashByte(hash,data[i]);
    remaining-=n;return true;
  };
  auto u16=[](const uint8_t *p){return uint16_t(uint16_t(p[0])<<8|p[1]);};
  uint16_t next=0;
  for(uint16_t sections=0;sections<=size.height;sections++) {
    uint8_t header[8];if(!take(header,2))return false;
    uint16_t y=u16(header);
    if(y==65535)return remaining==0&&hash==expectedPayload&&rowsHash(rows,size.height)==expectedRows;
    if(!take(header+2,6))return false;
    uint16_t n=u16(header+2),x=u16(header+4),w=u16(header+6);
    if(y<next||y>=size.height||!n||n>size.height-y||x>=size.width||!w||w>size.width-x)return false;
    if(uint32_t(n)*(4u+2u*w)+2u>remaining)return false;
    next=y+n;
    // A section's hashes come before its pixels: rows not drawn when it breaks off get hash 0 (unknown), so the
    // row state stays true to the screen for a resumed transfer.
    auto unknown=[&](uint16_t from,uint16_t to){for(uint16_t row=from;row<to;row++)rows[row]=0;return false;};
    for(uint16_t row=y;row<next;row++) {
      uint8_t value[4];if(!take(value,4))return unknown(y,row);
      uint32_t h=(uint32_t(value[0])<<24)|(uint32_t(value[1])<<16)|(uint32_t(value[2])<<8)|value[3];
      if(!h)return unknown(y,row);
      rows[row]=h;
    }
    for(uint16_t row=y;row<next;row++) {
      if(!take(scratch,2u*w)||!draw(row,x,w,scratch))return unknown(row,next);
    }
  }
  return false;
}
}
