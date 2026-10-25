#include "../firmware/src/serial_reply.h"
#include <cassert>
#include <limits>
#include <string>

static uint32_t ticks=0;
struct Clock {uint32_t now(){return ticks;} void pause(){++ticks;}};
struct Port {
    int capacity=4,room=4;uint32_t period=1,last=0;bool blocked=false,reject=false;unsigned calls=0;
    std::string bytes;
    int availableForWrite(){if(blocked)return 0;if(uint32_t(ticks-last)>=period){room=capacity;last=ticks;}return room;}
    size_t write(const uint8_t *data,size_t n){assert(n<=size_t(availableForWrite()));++calls;if(reject)return 0;bytes.append((const char*)data,n);room-=int(n);return n;}
};
int main(){
    const std::string text(10000,'x');
    { ticks=0;Port port;port.blocked=true;BoundedSerialReply<Port,Clock> reply(port);
      assert(reply.write((const uint8_t*)text.data(),text.size())==0);assert(ticks==250);assert(port.calls==0);
      assert(reply.write((const uint8_t*)text.data(),text.size())==0);assert(ticks==250); }
    { ticks=0;Port port;BoundedSerialReply<Port,Clock> reply(port);
      for(auto c:text)assert(reply.write((const uint8_t*)&c,1)==1);
      assert(port.bytes==text);assert(ticks<5000); } // long streamed logs remain complete while the host reads
    { ticks=0;Port port;BoundedSerialReply<Port,Clock> reply(port);ticks=10000;
      assert(reply.write((const uint8_t*)"hello",5)==5); } // scan/setup work before the first output consumes no TX budget
    { ticks=0;Port port;BoundedSerialReply<Port,Clock> reply(port);
      assert(reply.write((const uint8_t*)"abcd",4)==4);port.blocked=true;
      assert(reply.write((const uint8_t*)text.data(),text.size())==0);assert(ticks==250); }
    { ticks=0;Port port;port.capacity=port.room=1;port.period=100;BoundedSerialReply<Port,Clock> reply(port);
      assert(reply.write((const uint8_t*)text.data(),text.size())<text.size());assert(ticks==5000); }
    { ticks=std::numeric_limits<uint32_t>::max()-100;Port port;port.blocked=true;
      auto began=ticks;BoundedSerialReply<Port,Clock> reply(port);
      assert(reply.write((const uint8_t*)"x",1)==0);assert(uint32_t(ticks-began)==250); }
    { ticks=0;Port port;port.reject=true;BoundedSerialReply<Port,Clock> reply(port);
      assert(reply.write((const uint8_t*)"x",1)==0);assert(ticks==250); }
}
