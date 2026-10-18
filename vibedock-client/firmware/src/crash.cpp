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
uint32_t crashPc = 0, crashCause = 0, crashAddress = 0;
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
  crashReason = r.reason; crashPc = r.epc1; crashCause = r.exccause; crashAddress = r.excvaddr;
  char text[120];
  const char *what = r.reason == 253 ? "stack overflow" : r.reason == 254 ? "abort (e.g. out of memory)"
                   : r.reason == 2 ? "exception" : r.reason == 3 ? "software watchdog" : r.reason == 1 ? "hardware watchdog" : "crash";
  snprintf(text, sizeof(text), "%s (reason %u, cause %u at 0x%08x, address 0x%08x), stack %u bytes free",
           what, unsigned(r.reason), unsigned(r.exccause), unsigned(r.epc1), unsigned(r.excvaddr), unsigned(r.stackFree));
  cached = text;
  return cached;
}
// The crash record for X-VibeDock-Diag: "<pc>;cause=<exccause>;fault=<excvaddr>" (platform.h).
String crashHeader() {
  lastCrash();
  if (!crashReason) return String();
  char text[48]; snprintf(text, sizeof(text), "%lx;cause=%lu;fault=%lx", (unsigned long)crashPc, (unsigned long)crashCause, (unsigned long)crashAddress);
  return text;
}
void crashBegin() { lastCrash(); }
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
#include <esp_idf_version.h>
#include <esp_private/panic_internal.h>
#if __has_include(<esp_memory_utils.h>)
#include <esp_memory_utils.h>
#else
#include <soc/soc_memory_layout.h>
#endif
#if ESP_ARDUINO_VERSION_MAJOR >= 3
#include <esp32-hal.h>
#elif defined(__riscv)
#include <riscv/rvruntime-frames.h>
#else
#include <freertos/xtensa_context.h>
#endif

namespace {
// RTC memory that a reset (also a panic or a watchdog) leaves as it is; after a power cut it holds anything.
RTC_NOINIT_ATTR client_policy::DiagRecord rtcDiag;
// What the panic handler saw: the instruction (code[0]) and the code addresses that led there (Arduino core 3: its
// backtrace; core 2: the return address), the cause and the faulting address where the core gives them, and after
// abort() or a failed assert its text. Decoded with the firmware's ELF: addr2line -pfiaC -e firmware.elf <code...>.
struct PanicRecord {
  uint32_t magic, boot, code[6], cause, address;
  uint8_t codes, length; char text[58];
  uint32_t check;
};
constexpr uint32_t PANIC_MAGIC = 0x56445032;  // "VDP2"
RTC_NOINIT_ATTR PanicRecord rtcPanic;
PanicRecord lastPanic{}; bool panicRead = false;
// In IRAM and without calls into flash: the panic handler may run with the flash cache off.
IRAM_ATTR uint32_t panicCheck(const PanicRecord &r) {
  const uint8_t *bytes = reinterpret_cast<const uint8_t *>(&r); uint32_t hash = 2166136261u;
  for (size_t i = 0; i < offsetof(PanicRecord, check); i++) { hash ^= bytes[i]; hash *= 16777619u; }
  return hash;
}
IRAM_ATTR void panicBegin(uint32_t pc) {
  PanicRecord &r = rtcPanic;
  r.magic = PANIC_MAGIC; r.boot = rtcDiag.boot; r.code[0] = pc; r.codes = 1; r.cause = 0; r.address = 0; r.length = 0;
#if ESP_IDF_VERSION_MAJOR >= 5
  const char *text = g_panic_abort ? g_panic_abort_details : nullptr;  // ESP-IDF 4 keeps it to itself
#else
  const char *text = nullptr;
#endif
  if (text && esp_ptr_internal(text))
    while (r.length < sizeof(r.text) && text[r.length]) { r.text[r.length] = text[r.length]; r.length++; }
}
IRAM_ATTR void panicEnd() { rtcPanic.check = panicCheck(rtcPanic); }
}

#if ESP_ARDUINO_VERSION_MAJOR >= 3
// Core 3 wraps the IDF's panic handler itself and calls this one with its backtrace (RISC-V: the stack pointer, then
// the code addresses found on the stack). Registered in crashBegin().
IRAM_ATTR void recordPanic(arduino_panic_info_t *info, void *) {
  panicBegin(uint32_t(info->pc));
  PanicRecord &r = rtcPanic;
#if defined(__riscv)
  unsigned int from = 1;  // [0] is the stack pointer
#else
  unsigned int from = 0;
#endif
  for (unsigned int i = from; i < info->backtrace_len && r.codes < 6; i++) if (info->backtrace[i] != r.code[r.codes - 1]) r.code[r.codes++] = info->backtrace[i];
  panicEnd();
}
#else
// Core 2: linked in front of the IDF's panic handler (-Wl,--wrap=esp_panic_handler); that one then prints the
// backtrace and resets.
extern "C" void __real_esp_panic_handler(panic_info_t *info);
extern "C" IRAM_ATTR void __wrap_esp_panic_handler(panic_info_t *info) {
  panicBegin(uint32_t(info->addr));
  PanicRecord &r = rtcPanic;
  if (info->frame) {
#if defined(__riscv)
    auto *frame = static_cast<const RvExcFrame *>(info->frame);
    r.cause = frame->mcause; r.address = frame->mtval; r.code[r.codes++] = frame->ra;
#else
    auto *frame = static_cast<const XtExcFrame *>(info->frame);
    r.cause = frame->exccause; r.address = frame->excvaddr;
    r.code[r.codes++] = (uint32_t(frame->a0) & 0x3fffffffu) | (r.code[0] & 0xc0000000u);  // windowed call: top bits from the pc
#endif
  }
  panicEnd();
  __real_esp_panic_handler(info);
}
#endif

namespace platform {
// The panic of the run before, once after the reset that followed it (not after a power cut).
const PanicRecord *panicBefore() {
  if (!panicRead) {
    panicRead = true;
    auto reason = esp_reset_reason();
    bool afterPanic = reason == ESP_RST_PANIC || reason == ESP_RST_INT_WDT || reason == ESP_RST_TASK_WDT || reason == ESP_RST_WDT;
    if (afterPanic && rtcPanic.magic == PANIC_MAGIC && rtcPanic.check == panicCheck(rtcPanic) && rtcPanic.length <= sizeof(rtcPanic.text) && rtcPanic.codes >= 1 && rtcPanic.codes <= 6)
      lastPanic = rtcPanic;
    rtcPanic.magic = 0;
  }
  return lastPanic.magic ? &lastPanic : nullptr;
}
// The text of an abort or assert, for the log and the header: only characters that keep both readable.
String panicText(const PanicRecord &r) {
  String text;
  for (uint8_t i = 0; i < r.length; i++) {
    char c = r.text[i];
    text += isalnum((unsigned char)c) || strchr(" .:_/()-", c) ? c : ' ';
  }
  text.trim();
  return text;
}
String codes(const PanicRecord &r, const char *separator, const char *prefix) {
  String text;
  for (uint8_t i = 0; i < r.codes && i < 6; i++) { if (i) text += separator; text += prefix + String(r.code[i], HEX); }
  return text;
}
String lastCrash() {
  auto *r = panicBefore();
  if (!r) return String();
  String text = "pc " + codes(*r, " < ", "0x");
  if (r->cause || r->address) text += ", cause " + String(r->cause) + ", address 0x" + String(r->address, HEX);
  String abort = panicText(*r);
  return abort.isEmpty() ? text : text + ", " + abort;
}
String crashHeader() {
  auto *r = panicBefore();
  if (!r) return String();
  String text = codes(*r, ",", "");
  if (r->cause || r->address) text += ";cause=" + String(r->cause) + ";fault=" + String(r->address, HEX);
  String abort = panicText(*r);
  return abort.isEmpty() ? text : text + ";abort=" + abort;
}
void crashBegin() {
  panicBefore();  // the record of the run before, before a new panic overwrites it
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  set_arduino_panic_handler(recordPanic, nullptr);
#endif
}
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
