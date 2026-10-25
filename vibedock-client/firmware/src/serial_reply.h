#pragma once
#include <cstddef>
#include <cstdint>
#include <algorithm>

// No buffer of our own. Native USB writes are nonblocking; wait only for a solicited response, and abandon it
// if the host stops reading. Clock supplies now()/pause() so backpressure is reproducible without hardware.
template<class Port,class Clock> class BoundedSerialReply {
    Port &port;
    Clock clock;
    uint32_t began=0,lastProgress=0;
    bool started=false,stopped=false;
public:
    explicit BoundedSerialReply(Port &port):port(port){}
    size_t write(const uint8_t *bytes,size_t length){
        if(stopped)return 0;
        if(!started){started=true;began=lastProgress=clock.now();}
        size_t sent=0;
        while(sent<length){
            uint32_t now=clock.now();
            if(uint32_t(now-lastProgress)>=250 || uint32_t(now-began)>=5000){stopped=true;break;}
            int room=port.availableForWrite();
            size_t n=room>0?port.write(bytes+sent,std::min(length-sent,size_t(room))):0;
            if(n){sent+=n;lastProgress=clock.now();}
            else clock.pause();
        }
        return sent;
    }
};
