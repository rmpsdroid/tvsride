# TVSRide mobile design system

## Product personality

TVSRide is a practical motorcycle companion. The interface should feel calm, clear and dependable, with live vehicle information taking priority over configuration and protocol details.

The design is:
- **Ride-first:** connection and current vehicle status are immediately visible.
- **Readable:** strong hierarchy, clear labels and tabular numerals for measurements.
- **Progressively disclosed:** everyday controls first; advanced diagnostics only when requested.
- **Honest:** missing or stale data is never represented as a live reading.
- **Model-aware:** only supported vehicle capabilities are presented as available.

## Navigation

The three primary destinations are:

1. **Ride** — connection, live telemetry, navigation status and quick riding controls.
2. **Trips** — locally recorded ride history and totals.
3. **Settings** — vehicle setup, connection, notifications, navigation, display, appearance, privacy and advanced options.

Diagnostics are reachable from Settings → Advanced & Diagnostics, not as a primary destination.

## Colour

Use neutral surfaces and one restrained blue accent. Colour must communicate state, not decoration.

| Token | Light | Dark | Use |
|---|---|---|---|
| Canvas | `#FFFFFF` | `#0A0B0D` | App background |
| Surface | `#F7F7F7` | `#16181C` | Cards and grouped controls |
| Primary | `#0052FF` | `#0052FF` | Primary actions and selected controls |
| Text | `#0A0B0D` | `#FFFFFF` | Main content |
| Muted text | `#5B616E` | `#A8ACB3` | Supporting labels |
| Border | `#DEE1E6` | `#5B616E` | Subtle separation |
| Connected | `#05B169` | `#05B169` | Connection-ready state |
| Warning | `#F4B000` | `#F4B000` | Attention / retrying |
| Error | `#CF202F` | `#CF202F` | Failed / unavailable state |

Never communicate status through colour alone. Pair it with text and, where useful, an icon.

## Typography

Use Android's system sans-serif for interface text. Use system monospace with tabular numerals for speed, distance, time and other measurements. Avoid shipping a proprietary font solely for branding.

- Screen title: 28–30 sp, regular.
- Section title: 16–18 sp, semibold.
- Body: 14–16 sp.
- Supporting text: 12–14 sp.
- Primary speed reading: 56–60 sp, tabular numerals.
- Unit labels should be smaller than the measured value.

## Spacing and shape

Use a consistent 4 dp rhythm:
- 4 dp: icon/text micro-spacing.
- 8 dp: compact grouping.
- 12 dp: card internal grouping.
- 16 dp: standard screen gutters and card padding.
- 24 dp: section separation.
- 32 dp: major screen separation.

Use 12–16 dp for compact controls and 20–24 dp for prominent dashboard surfaces. Interactive controls must have at least a 48 dp touch target.

## Component rules

### Connection status
Always distinguish:
- Disconnected
- Connecting / reconnecting
- Bluetooth connected but waiting for cluster data
- Connected with fresh telemetry
- Connected with stale telemetry

A Bluetooth connection alone does not prove that telemetry is flowing.

### Live telemetry
Current speed is the strongest visual element on Ride. Fuel, RPM and range are secondary. Less frequently used values belong in an expandable vehicle-information section.

Show an em dash when a value is unavailable. Hide stale live readings rather than presenting an old speed as current. Show freshness in plain language.

### Cards and actions
Cards group related information; they should not turn every setting into an oversized panel. Use one clear primary action per screen or state. Prefer direct labels over ambiguous icons.

### Settings
Group options by user task. Explain a setting before requesting a permission. Keep protocol details, raw frames and test controls under Advanced & Diagnostics.

### Accessibility and riding safety
Use readable contrast in daylight and low light. Avoid dense text and small touch targets. Do not encourage interaction with settings while the scooter is moving.

## Current implementation baseline

The first redesign milestone introduces:
- Ride / Trips / Settings bottom navigation.
- A Ride dashboard with current speed, fuel, RPM, range and expandable vehicle information.
- A dedicated local Trips history screen.
- Diagnostics reached from Settings.
- Existing Bluetooth and cluster-control actions retained.

The Settings page is still being reorganized into dedicated categories. This is an intentional next step, not a claim that the full redesign is complete.
