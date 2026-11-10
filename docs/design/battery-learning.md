[VibeDock](../../README.md) › **Design: battery level learned by the server**

# Battery level learned by the server

Status: design, nothing implemented yet.

Goal: a battery percentage in the administration that is earned from the measurements of each display itself. The
firmware only measures the cell voltage (`battery_mV` in the device metrics) and estimates a coarse step from a fixed
curve; the server learns what the voltage of this cell, charger and board means.

## Rules

1. **No foreign calibration.** What was learned on one device is never applied to another, not even to one of the same
   board: cell, cable, charger, voltage divider and age differ. Reference data (below) never calibrates a device.
2. **Off until switched on.** A new device shows its battery as `unknown` in the administration. After an explicit
   activation per device it uses only the conservative base curve of the firmware.
3. **Learned from its own metrics.** Only the metrics the server stored for this device are used.
4. **Seeded is not learned.** Taking over reference values needs an explicit administrator action and is marked
   `seeded`, never `learned`. A reference may only appear as a comparison in the diagnosis ("the full voltage is in the
   range of known 1S Li-ion references").

## What counts as a discharge

A rise of the voltage proves nothing: it can be the recovery after a load peak, noise or another power source. The
server therefore never derives "charging" from the voltage. It knows two things only: *discharge confirmed* and
*nothing learned*.

- A **decline** starts with the first measurable fall beyond the noise of the measurement, over several readings.
- A decline is **confirmed as a discharge** when it ends in a failure: the readings stop, the last value lies in the
  limit range, and the next start is a `power-on` with an unknown run before. A failure at a high voltage is a power cut or
  a pulled cable and proves nothing.
- A decline is also confirmed when the voltage **reaches the limit range**, without waiting for the failure. The first
  limit range is everything below the lowest step of the base curve (3520 mV), for this device only.
- A decline that ends before (the voltage rises, USB or a charger) stays a candidate and is dropped. It teaches nothing
  and is not called charging.
- Rises and flat stretches teach nothing.

A confirmed discharge teaches retroactively from the start of the decline to the failure. The failure is the anchor for
0 %; the start of the decline is the upper anchor and is marked as such (it is not necessarily a full cell).

## Smoothed metrics

The stored raw values stay as they are. A smoothed series is derived when reading (no migration, the method can improve
without losing data):

- median of three against outliers (radio load pulls the voltage down for a moment), then a moving average;
- reset at a new run (boot id) and at gaps of more than about 15 minutes; the first reading after a start does not count;
- columns `smoothed_mV` and the slope in mV/h, and a neutral phase `flat` or `decline_candidate`; never "charging";
- in the export (CSV, Excel) and as a second line next to the raw voltage in the metrics view.

## Percentage in the administration

- Shown only when enough is learned: at least one confirmed discharge. Until then `unknown`, even when switched on.
- The value comes from the learned curve of this device and is marked `learned` (or `seeded`).
- **Contact lost at a low level:** a device that stops contacting the server while its last value lies in the limit range
  shows 0 % once the loss is confirmed (no pull for about three expected intervals). If it comes back with a clearly
  higher voltage, the 0 % was an empty cell that was charged; the value is corrected and the event stays in the log.
- Contact lost at a higher level is shown as offline with the last value and its age, never as 0 %.
- Later: the server draws the battery icon of the display; it can use this level instead of the firmware's step of 20 %.

## Reference data

Measurements of one device (for example a Waveshare 1.54 with a known cell) may be kept as a referenced data set, only
to test the learning, validate conservative starting limits, find differences between builds and document optional
example profiles later:

- board model and hardware revision; chemistry, cell count and, if known, capacity;
- observed voltages, discharge phases and the result of the learning; firmware version;
- an anonymised or no device identifier.

Example: a 1.54-inch e-paper on firmware 0.6.2, one cell, capacity unknown. The voltage fell from 4170 mV to 3290 mV in
90 minutes (readings every 5 minutes), then the display failed and started again as `power-on` after the cell was
charged. That decline is confirmed, the rise after it is not classified.

## Open points

- Noise limit, minimum length of a decline and gap limit are fixed with the first data.
- How the upper anchor is refined when several confirmed discharges exist.
- Whether the firmware should report the last voltage in its next start line (`previousMillivolts`), which would confirm a
  failure without an own reading shortly before it.
