"""Run the real quick-boot handling with an in-memory filesystem/clock; no hardware access."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / "firmware/src/main.cpp").read_text()
count = source[source.index("uint8_t countQuickBoot() {"):source.index("// Once the device has run for quickBootMs")]
handling = source[source.index("void settleQuickBoot() {"):source.index("// ---- Wi-Fi setup portal")]
prefix = r'''
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <string>
#include "client_policy.h"
using std::min;using std::max;
#define F(x) x
struct String {std::string s;String(const char *s):s(s){} String(unsigned n):s(std::to_string(n)){} };
String operator+(String a,String b){return String((a.s+b.s).c_str());}
uint32_t now=0,helloAt=UINT32_MAX;bool updated=false,quickBootPending=false,usbBootConfirmed=false,setupRequested=false;
bool bootFile=false;uint8_t savedBoots=0;unsigned deletedConfiguration=0,restarted=0;
uint32_t millis(){return now;}void delay(unsigned ms){now+=ms;}
struct File {bool exists;operator bool(){return exists;}int read(){return savedBoots;}void write(uint8_t n){savedBoots=n;bootFile=true;}void close(){};};
struct Files {File open(const char*,const char *mode){return {mode[0]=='w'||bootFile};}
 bool remove(const char *path){if(std::string(path)=="/boots")bootFile=false;else ++deletedConfiguration;return true;}} LittleFS;
bool newFirmware(){return updated;}
namespace platform {bool powerCycleStart(){return true;}void eraseWifi(){++deletedConfiguration;}}
enum class Step {None};enum class Intent {FactoryReset};
void message(const char*,Step,bool){}void showDetail(const char*){}void logEvent(const char*){}void logEvent(String){}void diagIntent(Intent){}
struct Esp {void restart(){++restarted;}} ESP;
void serialPoll(){if(now>=helloAt){usbBootConfirmed=true;quickBootPending=false;LittleFS.remove("/boots");}}
'''
suffix = r'''
void reset(uint8_t previous,uint32_t hello=UINT32_MAX){now=0;helloAt=hello;updated=false;quickBootPending=false;usbBootConfirmed=false;setupRequested=false;bootFile=true;savedBoots=previous;deletedConfiguration=restarted=0;}
int main(){
 reset(1,500);handleQuickBoots();assert(usbBootConfirmed&&!setupRequested&&!deletedConfiguration&&!restarted&&!bootFile);
 reset(2,500);handleQuickBoots();assert(usbBootConfirmed&&!setupRequested&&!deletedConfiguration&&!restarted&&!bootFile);
 reset(2,0);handleQuickBoots();assert(usbBootConfirmed&&!setupRequested&&!deletedConfiguration&&!bootFile);
 reset(1);handleQuickBoots();assert(setupRequested&&!deletedConfiguration&&!restarted);assert(now>=client_policy::quickBootMs);
 reset(2);handleQuickBoots();assert(restarted==1&&deletedConfiguration>0&&!setupRequested);assert(now>=client_policy::usbBootGraceMs);
 reset(2);updated=true;handleQuickBoots();assert(!bootFile&&!setupRequested&&!deletedConfiguration&&!restarted);
 reset(0);handleQuickBoots();assert(savedBoots==1&&quickBootPending&&!setupRequested&&!deletedConfiguration);
}
'''
with tempfile.TemporaryDirectory() as directory:
    cpp=Path(directory)/"quick_boot.cpp"
    executable=Path(directory)/"quick_boot"
    cpp.write_text(prefix+count+handling+suffix)
    subprocess.run(["c++","-std=c++17","-Wall","-Wextra","-fsanitize=address,undefined","-I",str(root/"firmware/src"),str(cpp),"-o",str(executable)],check=True)
    subprocess.run([str(executable)],check=True)
print("Quick boot: USB confirmations preserve settings; deliberate setup/factory-reset paths remain available")
