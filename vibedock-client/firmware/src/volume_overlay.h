#pragma once
#include <stdint.h>
#include <string.h>
// The S3 1.54-inch panel has no readback wire. Save the underlying pixels as they are sent, so hiding the
// volume notice restores the latest picture without a network request (also when the server is unreachable).
namespace sound_policy {
class VolumeOverlay {
  uint16_t pixels[200 * 48] = {};
  bool rows[48] = {};
  bool active = false;
  uint32_t started = 0;
 public:
  static constexpr int x=20, y=96, width=200, height=48, screenWidth=240;
  static constexpr uint32_t duration=1600;
  void capture(int row,const uint16_t *data,int rowWidth) {
    if(row<y || row>=y+height || rowWidth!=screenWidth) return;
    memcpy(pixels+(row-y)*width,data+x,width*sizeof(uint16_t)); rows[row-y]=true;
  }
  // A delta changes only part of an already captured row; keep the rest for overlay restoration.
  void captureSpan(int row,int left,const uint16_t *data,int count) {
    if(row<y||row>=y+height||!rows[row-y])return;
    for(int i=0;i<count;i++)if(left+i>=x&&left+i<x+width)pixels[(row-y)*width+left+i-x]=data[i];
  }
  void show(uint32_t now) { active=true; started=now; }
  bool visible(uint32_t now) const { return active && uint32_t(now-started)<duration; }
  bool expired(uint32_t now) const { return active && !visible(now); }
  void dismiss() { active=false; }
  const uint16_t *savedRow(int row) const { return row>=0 && row<height && rows[row] ? pixels+row*width : nullptr; }
};
}
