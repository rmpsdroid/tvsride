# TVSRide 2.0 — UX and visual design foundation

**Status:** Proposed design baseline  
**Scope:** Information architecture, screen responsibilities, settings inventory, visual principles  
**Implementation status:** First UI milestone implemented on this branch: Ride / Trips / Settings navigation, a ride-first dashboard, local Trips history, and a Settings entry to Diagnostics. BLE protocol and service code have not been changed.

## 1. Product design goals

TVSRide should feel like a focused motorcycle companion, not a protocol-debugging tool.

1. **Simple at first glance:** the rider can understand connection and vehicle status immediately.
2. **Feature-rich without clutter:** every supported option is discoverable, but advanced controls do not crowd the main dashboard.
3. **Ride-first:** live vehicle information and connection health take priority over configuration.
4. **Honest data:** unavailable or stale readings are shown as unavailable/stale, never as invented zeroes.
5. **Model-aware:** show controls that the selected vehicle and current firmware can actually support.
6. **Local-first:** explain clearly that ride data and personal information stay on the phone unless the user exports or shares them.
7. **Safe interaction:** large, readable information; avoid requiring interaction while the scooter is moving.

## 2. Recommended navigation

Replace the current four bottom tabs (Home, More, Overview, Log) with three primary destinations:

| Tab | Purpose |
|---|---|
| **Ride** | Connection status, live dashboard, quick controls and last parked information |
| **Trips** | Local ride history, ride details and totals |
| **Settings** | All configuration, permissions, vehicle management and app preferences |

Move **Diagnostics** into Settings → Advanced → Diagnostics. It remains fully accessible, but raw frames and protocol details no longer compete with everyday riding features.

The current UI reference says three tabs while the implementation currently has four. This document supersedes that older navigation proposal for TVSRide 2.0.

## 3. Screen specifications

### 3.1 Ride — the primary screen

The Ride screen should answer three questions without scrolling:

- Is my scooter connected?
- Is the app receiving fresh information?
- Are the important riding features ready?

Recommended hierarchy:

1. **Top bar:** TVSRide, selected vehicle/model and a clear connection indicator.
2. **Connection card:** current state and one contextual action:
   - Not configured → Find scooter
   - Disconnected → Connect / Retry
   - Scanning or connecting → show progress and allow cancel
   - Connected, waiting for data → explain that the Bluetooth link is up but telemetry has not arrived
   - Connected with fresh data → show Connected and last-update freshness
   - Reconnecting → show retry state and a Troubleshoot action
3. **Live vehicle panel:** current speed as the most prominent value; fuel and range as secondary values. RPM and other readings can appear in a compact secondary row.
4. **Quick status:** Navigation, Calls and Messages, each with an enabled/disabled/permission-needed state.
5. **Quick actions:** Find my scooter and Last parked.
6. **All vehicle data:** expandable section for odometer, trip distance, average/top speed, RPM, battery, temperature, service status, check-engine status, economy and firmware when the selected model supplies them.

Do not put every telemetry value in an equally prominent card. The first view is for the few values that matter most; the expandable section is for detail.

When disconnected, replace the live values with a useful connection panel and a direct recovery action. Do not leave a grid of empty tiles.

### 3.2 Trips

Keep ride history separate from configuration.

- Summary: total rides and total recorded distance.
- Ride list: date/time and the trip facts actually captured.
- Ride detail: available duration, distance and telemetry summary.
- Last parked: location and timestamp when available.
- Export: keep the data export action available from this area and from Settings → Data & Privacy.

Do not imply that route replay or a GPS track exists unless the app has explicitly recorded it with the required permission.

### 3.3 Settings

Use a short list of clear categories. Each category opens its own screen rather than extending one very long scrolling page.

| Category | Options to organize here |
|---|---|
| **My scooter** | Selected vehicle/model, find or change scooter, scan, connection details, disconnect |
| **Connection** | Auto-connect near vehicle, reconnect behaviour, resume after reboot, Android background/battery guidance, Bluetooth permissions |
| **Navigation** | Navigation mirroring, supported map apps, add/remove custom map apps |
| **Calls & messages** | Phone/app calls, messages, any-app notifications, optional message text, call type, extra call/message apps, notification access |
| **Cluster display** | Rider name, cluster brightness and supported cluster calibration controls |
| **Ride preferences** | Ride-session behaviour and supported vehicle-specific preferences |
| **Appearance** | Light, dark or system theme; screen-awake option for testing/use where appropriate |
| **Data & privacy** | Local-data explanation, export rides/settings/parked data, privacy controls |
| **Advanced & diagnostics** | Live logs, raw BLE frames, log export, protocol confidence, test/simulator tools and build information |
| **About** | App version, supported models, open-source notices and support information |

### 3.4 Diagnostics

Diagnostics is for troubleshooting, not everyday use.

- Connection lifecycle timeline: scan → connect → services discovered → notifications enabled → first telemetry frame.
- Last successful connection and last received frame.
- Bluetooth state and reconnect attempts.
- Clock-sync state and last successful synchronization.
- Notification listener and phone-call permission state.
- Filterable logs and raw BLE frames.
- Export a redacted diagnostic bundle.

Provide a simple **Copy diagnostics** or **Export logs** action. Never expose contact details, message bodies, full device identifiers or parked coordinates in a shared diagnostic export.

## 4. Feature availability and discoverability

Every feature should have one of four clear states:

- **Available:** works for this scooter and current Android permissions.
- **Needs permission:** explain why it is needed and provide a direct Settings action.
- **Experimental:** available for testing, clearly labelled as not yet hardware-verified.
- **Not supported by this vehicle:** explain the limitation in the capability list; do not show a non-functional switch.

The selected scooter model controls which vehicle-specific options appear. Do not show a universal control that silently does nothing on a particular model.

Use progressive disclosure:
- Everyday controls appear on Ride.
- Normal configuration appears in Settings categories.
- Protocol, raw-frame and test controls stay under Advanced.
- Optional app-specific filters appear only when the user opens the relevant notification category.

Add a Settings search field once the settings inventory grows enough to justify it.

## 5. First-run setup

Use a short, guided setup rather than presenting every setting at once:

1. Welcome and a brief explanation of local Bluetooth operation.
2. Find the scooter and select the correct model.
3. Request Bluetooth permissions needed to connect.
4. Connect and confirm that the cluster is responding.
5. Offer notification access when the user enables navigation/call/message mirroring.
6. Offer Android background/battery guidance for reliable automatic connection.
7. Finish on the Ride screen.

Do not make optional notification permissions a blocker for basic Bluetooth telemetry.

## 6. Visual design direction

### Personality

Clean, modern, confident and practical — a motorcycle dashboard companion. Avoid a finance/crypto visual language and avoid decorative UI that competes with the vehicle information.

### Layout and typography

- One consistent spacing scale: 4, 8, 12, 16, 24 and 32 dp.
- Minimum 48 dp touch targets for interactive controls.
- Large tabular numerals for speed and telemetry.
- Clear hierarchy: screen title, section title, value, supporting label.
- Use cards only when they group related information; do not put every setting in its own oversized card.
- Keep labels sentence-case and concise.
- Use consistent icons and control placement.

### Colour and themes

- Support Light, Dark and System modes.
- Use neutral surfaces with one restrained brand accent.
- Reserve semantic colours for state: green = connected/ready, amber = attention/retrying, red = error/disconnected where appropriate.
- Never use colour alone to communicate connection or permission state; include text and an icon.
- Maintain strong contrast outdoors and in low-light conditions.

### Interaction

- One primary action per screen/state.
- Prefer direct labels over ambiguous icons.
- Explain what a setting does before asking for a permission.
- Show immediate feedback after a setting changes.
- Avoid hidden gestures for essential functions.
- Do not place advanced technical controls beside everyday ride actions.

## 7. Current feature inventory to preserve

The existing implementation already contains useful capabilities that should be retained and reorganized:

- Vehicle scanning, model selection and Bluetooth connection.
- Auto-connect through Android companion-device presence.
- Navigation mirroring and custom map-app packages.
- Phone/app call and message notification mirroring.
- Optional message text and call-type details.
- Custom call and message app packages.
- Find My and last parked information.
- Rider name and cluster brightness/calibration controls.
- Ride history and local data export.
- Light/dark/system appearance.
- Keep-screen-on testing option.
- Protocol confidence information, pictograms, navigation-display explanation and raw logs.

Preserve verified protocol code and tests independently of the UI redesign. Do not assume that a feature is hardware-verified merely because its setting or code exists.

## 8. Implementation order

1. Approve this information architecture and screen responsibilities.
2. Create the shared design tokens and reusable UI components.
3. Build the Ride screen and connection-state presentation.
4. Build Settings categories and move existing options without changing their underlying behaviour.
5. Build Trips and Diagnostics destinations.
6. Add model-aware capability visibility and permission guidance.
7. Test on the actual Jupiter 110 in daylight, dark mode, disconnected state, reconnect state and a real ride.

## 9. Acceptance criteria

- A first-time user can find and connect the scooter without exploring multiple tabs.
- Connection state and telemetry freshness are understandable at a glance.
- All existing supported settings remain discoverable.
- Everyday users do not encounter raw protocol details unless they open Advanced.
- Unsupported or unverified features are never presented as working.
- No existing verified protocol behaviour is removed as part of a visual redesign.
- The interface remains readable and usable on a phone in both light and dark themes.


## 10. Implementation progress

### Milestone 1 — primary navigation and rider dashboard

Implemented in the current design branch:
- Replaced the four primary destinations with Ride, Trips and Settings.
- Added a Ride-first dashboard with connection state, telemetry freshness, current speed, fuel, RPM and range.
- Added expandable vehicle information.
- Hid live telemetry values when the connection is down or the last frame is stale.
- Moved ride history into its own Trips destination.
- Moved live logs behind Settings → Advanced & Diagnostics.
- Retained the existing Bluetooth actions, navigation mirroring, Find My, ride-resume toggle and cluster controls.

### Milestone 2 — Settings categories and connection-state clarity

Implemented on this branch:
- Replaced the long Settings scroll with a searchable category index and dedicated category pages.
- Organized scooter management, connection, navigation, calls & messages, cluster display, ride preferences, appearance, data & privacy, advanced diagnostics and About.
- Preserved the existing preference keys, package-picker flows, cluster controls, export action and diagnostics entry.
- Added direct notification-access guidance and made clear that notification access is optional for basic Bluetooth telemetry.
- Exposed Navigation mirroring and Keep Ride Mode active in Settings while retaining their Ride-screen quick controls.
- Corrected the Ride connection card so Connecting, Connected, Reconnecting and Disconnected are described consistently with the status pill.

### Remaining work

- Run CI unit tests and Android debug build for this milestone, then review the new APK.
- Review the category index and each Settings page on the target phone for readability and navigation.
- Test Bluetooth scanning, connection, telemetry freshness, reconnect and vehicle commands with the actual Jupiter 110.
- Continue with model-aware capability visibility and the fuller Diagnostics lifecycle/permission summary.
- Complete daylight, dark-mode and real-ride checks; screenshots without scooter hardware are not hardware verification.
