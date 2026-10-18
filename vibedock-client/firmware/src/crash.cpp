// What made the display restart, kept in memory that survives a reset (not a power cut):
// - ESP8266: the crash record of the core's crash callback (exception, watchdog, stack overflow, abort), because the
//   reset reason alone says only "Software/System restart" for a stack overflow or a failed allocation.
// - All boards: the diagnosis record (client_policy::DiagRecord) with the current start and its last phase, read
//   once after the next reset as the state of the start before.
#include <Arduino.h>
#include "client_policy.h"
#if defined(ESP8266)
#include <user_interface.h>

namespace {
constexpr uint32_t MAGIC = 0x56544352;  // "VTCR"
constexpr uint32_t SLOT = 32;           // RTC user memory block (4-byte units after the 512 SDK bytes)
constexpr uint32_t DIAG_SLOT = 40;      // after the crash record (6 blocks)
struct Record { uint32_t magic, reason, exccause, epc1, excvaddr, stackFree; };
uint32_t crashReason = 0;  // the core's reason of the crash record, 0 without one
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
  crashReason = r.reason;
  char text[120];
  const char *what = r.reason == 253 ? "stack overflow" : r.reason == 254 ? "abort (e.g. out of memory)"
                   : r.reason == 2 ? "exception" : r.reason == 3 ? "software watchdog" : r.reason == 1 ? "hardware watchdog" : "crash";
  snprintf(text, sizeof(text), "%s (reason %u, cause %u at 0x%08x, address 0x%08x), stack %u bytes free",
           what, unsigned(r.reason), unsigned(r.exccause), unsigned(r.epc1), unsigned(r.excvaddr), unsigned(r.stackFree));
  cached = text;
  return cached;
}
// The core's reset reason (rst_info), or the crash record's when there is one (it knows stack overflow and abort).
uint32_t resetCode() { lastCrash(); return crashReason ? crashReason : ESP.getResetInfoPtr()->reason; }
client_policy::ResetKind resetKind() {
  using K = client_policy::ResetKind;
  switch (resetCode()) {
    case REASON_DEFAULT_RST: return K::PowerOn;
    case REASON_EXT_SYS_RST: return K::External;
    case REASON_SOFT_RESTART: return K::Software;
    case REASON_EXCEPTION_RST: case 253: case 254: return K::Panic;
    case REASON_WDT_RST: case REASON_SOFT_WDT_RST: return K::Watchdog;
    case REASON_DEEP_SLEEP_AWAKE: return K::DeepSleep;
    default: return K::Other;
  }
}
bool loadDiag(client_policy::DiagRecord &r) { return ESP.rtcUserMemoryRead(DIAG_SLOT, reinterpret_cast<uint32_t *>(&r), sizeof(r)); }
void storeDiag(const client_policy::DiagRecord &r) {
  ESP.rtcUserMemoryWrite(DIAG_SLOT, reinterpret_cast<uint32_t *>(const_cast<client_policy::DiagRecord *>(&r)), sizeof(r));
}
}  // namespace platform
#else
#include <esp_attr.h>
#include <esp_system.h>

namespace {
// RTC memory that a reset (also a panic or a watchdog) leaves as it is; after a power cut it holds anything.
RTC_NOINIT_ATTR client_policy::DiagRecord rtcDiag;
}

namespace platform {
uint32_t resetCode() { return uint32_t(esp_reset_reason()); }
client_policy::ResetKind resetKind() {
  using K = client_policy::ResetKind;
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON: return K::PowerOn;
    // The reset button, or the USB port's reset (esptool, a serial monitor, Restart display in the administration).
    case ESP_RST_EXT: return K::External;
#if ESP_IDF_VERSION_MAJOR >= 5
    case ESP_RST_USB: return K::External;
#endif
    case ESP_RST_SW: return K::Software;
    case ESP_RST_PANIC: return K::Panic;
    case ESP_RST_INT_WDT: case ESP_RST_TASK_WDT: case ESP_RST_WDT: return K::Watchdog;
    case ESP_RST_BROWNOUT: return K::Brownout;
    case ESP_RST_DEEPSLEEP: return K::DeepSleep;
    default: return K::Other;
  }
}
bool loadDiag(client_policy::DiagRecord &r) { r = rtcDiag; return true; }
void storeDiag(const client_policy::DiagRecord &r) { rtcDiag = r; }
}  // namespace platform
#endif
