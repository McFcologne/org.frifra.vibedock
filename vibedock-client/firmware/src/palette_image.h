#pragma once
#include "client_policy.h"
#include <cstring>

namespace client_policy {
inline uint8_t parsePaletteFormat(const char *text,ImageSize &size,bool delta=false){
  if(std::strncmp(text,"indexed",7)!=0)return 0;
  uint8_t bits=text[7]>='1'&&text[7]<='8'?uint8_t(text[7]-'0'):0;
  return bits&&text[8]=='-'&&parseImageFormat(text+9,size,delta)?bits:0;
}
inline uint32_t paletteLength(const ImageSize &size,uint8_t bits,uint16_t count){return 2u+2u*count+((uint32_t(size.width)*bits+7)/8)*size.height;}
inline bool paletteBodyLength(const char *text,const ImageSize &size,uint8_t bits,uint32_t &length){
  uint32_t maximum=paletteLength(size,bits,uint16_t(1u<<bits)),n=0;if(!*text)return false;
  for(;*text;text++){if(*text<'0'||*text>'9'||n>maximum/10)return false;n=n*10+uint32_t(*text-'0');if(n>maximum)return false;}
  if(n<paletteLength(size,bits,1))return false;length=n;return true;
}
// Packed indices are MSB-first and each row starts at a byte boundary. Expand backwards in the existing RGB row.
inline bool expandPaletteRow(uint8_t *row,uint16_t width,uint8_t bits,const uint8_t *palette,uint16_t count=0){
  if(bits<1||bits>8)return false;
  if(!count)count=uint16_t(1u<<bits);
  for(int x=int(width)-1;x>=0;x--){uint16_t index=0;
    for(uint8_t b=0;b<bits;b++){uint32_t p=uint32_t(x)*bits+b;index=(index<<1)|((row[p/8]>>(7-p%8))&1);}
    if(index>=count)return false;
    row[2*x]=palette[2*index];row[2*x+1]=palette[2*index+1];
  }return true;
}
}
