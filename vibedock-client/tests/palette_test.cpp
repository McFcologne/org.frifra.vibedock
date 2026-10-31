#include "../firmware/src/palette_image.h"
#include <cassert>
#include "../firmware/src/delta_image.h"
#include <vector>
int main(){
  using namespace client_policy;ImageSize size;
  for(int bits:{1,2,3,4,5,6,7,8}){
    std::string format="indexed"+std::to_string(bits)+"-rgb565-be-17x16";
    assert(parsePaletteFormat(format.c_str(),size)==bits);
    assert(paletteLength(size,bits,1u<<bits)==uint32_t(2+(2<<bits)+16*((17*bits+7)/8)));
    std::vector<uint8_t> palette(2<<bits),row(34,0),expected(34,0);
    for(int i=0;i<(1<<bits);i++){palette[2*i]=i;palette[2*i+1]=255-i;}
    for(int x=0;x<17;x++){int index=(x*17)% (1<<bits);expected[2*x]=index;expected[2*x+1]=255-index;
      for(int b=0;b<bits;b++)if(index&(1<<(bits-1-b))){int pos=x*bits+b;row[pos/8]|=1<<(7-pos%8);}}
    assert(expandPaletteRow(row.data(),17,bits,palette.data()));assert(row==expected);
  }
  {
    ImageSize size{32,16};uint8_t palette[512]={},scratch[2*maxImageSide]={};
    std::vector<uint8_t> before(size.bytes(),0),after=before,patch;
    uint32_t rows[16],expected[16];
    for(int y=0;y<16;y++)rows[y]=rowHash(before.data()+y*64,64);
    for(int y=0;y<2;y++)for(int x=0;x<16;x++){after[y*64+2*x]=0xab;after[y*64+2*x+1]=0xcd;}
    for(int y=0;y<16;y++)expected[y]=rowHash(after.data()+y*64,64);
    patch.push_back(0);patch.push_back(1);patch.push_back(0xab);patch.push_back(0xcd);
    for(int v:{0,0,0,2,0,0,0,16})patch.push_back(v);
    for(int y=0;y<2;y++)for(int shift=24;shift>=0;shift-=8)patch.push_back(expected[y]>>shift);
    for(int i=0;i<16;i++)patch.push_back(0);patch.push_back(255);patch.push_back(255);
    uint32_t payload=2166136261u;for(auto b:patch)payload=hashByte(payload,b);
    size_t at=0;
    assert(parsePaletteFormat("indexed4-rgb565-delta-be-32x16",size,true)==4);
    assert(readDelta(size,patch.size(),payload,rowsHash(expected,16),rows,scratch,[&](uint8_t *data,size_t n){if(at+n>patch.size())return false;std::memcpy(data,patch.data()+at,n);at+=n;return true;},[&](int y,int x,int w,const uint8_t *data){std::memcpy(before.data()+2*(y*32+x),data,2*w);return true;},4,palette));
    assert(before==after);
    for(int bits:{1,2,3,4,5,6,7,8})assert(expandPaletteRow(scratch,1,bits,palette));
  }
  for(auto text:{"","indexed0-rgb565-be-16x16","indexed6-rgb565-be-9999x16","indexed6-rgb565-be-16x16junk","rgb565-be-16x16"})assert(!parsePaletteFormat(text,size));
}
