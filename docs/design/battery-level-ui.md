[VibeDock](../../README.md) › **Design: learned battery level UI**

# Learned battery level UI

Status: V1 concept. It describes the administration UI for a server-side, per-display battery estimate. It does not
change firmware behaviour or battery safety.

![V1 concept: an established, per-display battery estimate with calibration details](battery-level-ui-v1.png)

## Purpose

The battery UI must answer three things without making a device card noisy:

1. Does this display support a battery measurement?
2. Is a battery estimate enabled and trustworthy for *this* display?
3. Why is a percentage shown, missing, or no longer current?

The display owner chooses whether to enable an estimate. Boards normally ship without a battery; neither voltage nor a
charging signal proves that a battery is connected. The server learns only from metrics of this one display. It never
copies a calibration from another display, including one with the same board model.

## Card states

| Hardware and estimate state | Device-card presentation | User action |
| --- | --- | --- |
| Board cannot measure a battery | No battery section at all. This is a hardware capability, not a warning. | None. |
| Board can measure; estimate off | Compact **Battery estimate · Off** row with the sentence “Optional · learns only from this display’s metrics.” | **Enable battery estimate**. |
| Enabled; no confirmed discharge | **Learning** badge, no percentage, and “No percentage yet · waiting for a confirmed discharge.” | View calibration details; disable if unwanted. |
| Enabled; curve learned | Battery icon, percentage, **Established** badge, and a compact summary such as “learned from this display · 4 confirmed cycles.” | Expand calibration details. |
| Learned; current measurement is old | **Offline** badge, last percentage and age; never silently present it as current. | Open metrics or wait for the display. |
| Learned; contact was lost after a confirmed low voltage | **0 % · Contact lost** with a short explanation. | Open calibration details; it corrects itself after a later higher measurement. |
| External USB/charging state has no usable cell reading | **External power**; no invented percentage. | None. |

`Unknown` is reserved for an enabled estimate without a safe conclusion. It must not be used as a substitute for the
first state: unsupported boards simply do not show battery controls.

## Established card

The compact version is the normal device-card presentation:

```text
[ battery icon ]  67 %     Established
learned from this display · 4 confirmed cycles                 ⌃
```

- Green is reserved for a current, established estimate.
- Indigo/blue denotes **Learning**.
- Grey denotes **Off**, unavailable hardware, or an old measurement.
- Orange denotes **Offline** or an estimate that needs attention.
- Red is an error colour only; an empty battery is not an error.

The percentage and status must have text equivalents. Colour and the battery glyph are supplementary information.

## Expanded calibration details

Selecting the compact row reveals one calm card below the device information:

```text
Battery calibration

● Enabled ───────── ● Learning ───────── ● Established

  Voltage chart: faint raw measurements + prominent learned/smoothed line
  • confirmed discharge                  • last measurement: 4.03 V

  Confidence     High
  Last calibrated Today, 18:34

  [ Recalculate from metrics ]
  [ Reset learned estimate… ]

  Voltage-based estimate · this display only
```

The voltage chart makes the estimate auditable: raw points stay visually quiet, while the smoothed/learned line and
confirmed discharge markers explain the resulting curve. It belongs in the expanded view, never in every device card.

### Actions

- **Enable battery estimate** requires an explicit acknowledgement: the user has connected a rechargeable 1S
  Li-ion/LiPo cell and understands that this is a voltage-based estimate, not battery management.
- **Recalculate from metrics** recomputes the server-side estimate from this display’s retained measurements. It does
  not change the display firmware or any charging behaviour.
- **Reset learned estimate…** is a secondary, confirmation-protected maintenance action. It removes the learned curve,
  returns the device to **Learning**, and preserves the raw metrics.
- **Disable battery estimate** removes the estimate from the card; it must not delete raw metrics.

## V1 boundaries

V1 intentionally excludes:

- automatic battery-presence detection;
- estimates of capacity, health, chemistry, or remaining runtime;
- copying another device’s curve as a default;
- using the estimate to change charging, shutdown, CPU speed, Wi-Fi, or display brightness;
- a visible battery section on hardware without a supported measurement.

The explanatory text in every view is: “Voltage-based estimate · this display only.” It prevents a learned percentage
from being mistaken for a fuel gauge or a universal property of the board.

## Related documentation

- [Device metrics and support data](device-logs.md)
