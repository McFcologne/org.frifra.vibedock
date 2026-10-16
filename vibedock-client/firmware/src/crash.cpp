// What made the ESP8266 restart, kept in RTC memory across the restart: the reset reason alone says only
// "Software/System restart" for a stack overflow or a failed allocation. Read once after the restart.
#if defined(ESP8266)
#include <Arduino.h>
#include <user_interface.h>

namespace {
constexpr uint32_t MAGIC = 0x56544352;  // "VTCR"
constexpr uint32_t SLOT = 32;           // RTC user memory block (4-byte units after the 512 SDK bytes)
struct Record { uint32_t magic, reason, exccause, epc1, excvaddr, stackFree; };
}

extern "C" void custom_crash_callback(struct rst_info *info, uint32_t stack, uint32_t stackEnd) {
  Record r{MAGIC, info->reason, info->exccause, info->epc1, info->excvaddr, ESP.getFreeContStack()};
  (void)stack; (void)stackEnd;
  ESP.rtcUserMemoryWrite(SLOT, reinterpret_cast<uint32_t *>(&r), sizeof(r));
}

namespace platform {
String lastCrash() {
  static bool read = false; static String cached;
  if (read) return cached;
  read = true;
  Record r{};
  if (!ESP.rtcUserMemoryRead(SLOT, reinterpret_cast<uint32_t *>(&r), sizeof(r)) || r.magic != MAGIC) return cached;
  uint32_t none = 0; ESP.rtcUserMemoryWrite(SLOT, &none, sizeof(none));  // reported once
  char text[120];
  const char *what = r.reason == 253 ? "stack overflow" : r.reason == 254 ? "abort (e.g. out of memory)"
                   : r.reason == 2 ? "exception" : r.reason == 3 ? "software watchdog" : r.reason == 1 ? "hardware watchdog" : "crash";
  snprintf(text, sizeof(text), "%s (reason %u, cause %u at 0x%08x, address 0x%08x), stack %u bytes free",
           what, unsigned(r.reason), unsigned(r.exccause), unsigned(r.epc1), unsigned(r.excvaddr), unsigned(r.stackFree));
  cached = text;
  return cached;
}
}  // namespace platform
#endif
