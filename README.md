# Skywatch (CelesTrak)

A Hubitat driver for predicting satellite viewing windows and using them in automations. Track the ISS, Tiangong, Hubble, or a custom low-Earth-orbit satellite. No account, API key, companion server, or additional Hubitat library is needed.

Orbital elements come from [CelesTrak](https://celestrak.org/). The hub calculates the satellite's position, your viewing direction, sky darkness, and Earth's shadow locally. Observer coordinates stay on the hub.

## Install

1. In Hubitat, open **Drivers Code → New Driver**.
2. Import the [raw driver](https://raw.githubusercontent.com/b69ca/hubitat-skywatchcelestrak/main/SkywatchCelesTrak.groovy), or paste its contents, then **Save**.
3. Open **Devices → Add Device → Virtual**. Choose **Skywatch (CelesTrak)** from the user drivers and give the device a name, such as `Skywatch ISS`. Older Hubitat interfaces put the driver choice on the virtual-device form.
4. Open the device's **Preferences**, choose your settings, and **Save Preferences**. Check that your hub's location and time zone are correct. Enter observer altitude in meters if known; otherwise leave it at zero.
5. Allow a few minutes for the initial calculation. **Prediction Status** should become `ready`. **Pass State** will show `upcoming`, `approaching`, `visible`, or `none`.

Create **one device per satellite**. Multiple rules can use the same device. Duplicate devices for the same satellite do not share a download cache or request limit.

To update, import the source again, save it, then press **Initialize** on each Skywatch device. Cached elements and the request limit survive initialization.

## What a viewing window means

A window is a continuous interval when all three conditions hold:

- The satellite is above your minimum elevation.
- The Sun is below your selected darkness threshold.
- The satellite is fully outside Earth's shadow, including the partial-shadow region.

The interval must also meet your minimum duration and peak elevation. Its start and end describe the **visible portion**, which may be shorter than the satellite's full horizon-to-horizon pass. Directions are true compass directions, not magnetic bearings. Peak elevation is measured within that visible portion.

`visible` means the predicted geometry qualifies **now**. Clouds, buildings, mountains, light pollution, the satellite's brightness and orientation, and orbital maneuvers can prevent a sighting. Hubble and arbitrary custom satellites may be difficult to see even when the geometry qualifies. There is no brightness or weather model.

## Preferences

| Preference | Default | Choices / purpose |
| --- | --- | --- |
| Satellite | ISS | ISS (25544), Tiangong (48274), Hubble (20580), or custom catalog number |
| Custom NORAD catalog number | Empty | Used only when Custom is selected; must have supported public GP elements |
| Use custom observer coordinates | Off | Otherwise uses the hub's latitude and longitude |
| Custom latitude / longitude | Empty | Decimal degrees; north/east positive, south/west negative; zero is valid |
| Observer altitude | 0 m | −500 to 9,000 m above sea level; applies to either location source |
| Look ahead | 48 hours | 12, 24, 48, or 72 hours |
| Minimum viewing elevation | 10° | 5–60°; raise this to avoid trees or low horizon obstructions |
| Minimum peak elevation | 20° | 5–90° during the visible portion; filters low passes |
| Minimum visible duration | 60 seconds | 30–600 seconds |
| Sky darkness | Sun below −6° | −0.833°, −3°, −6°, −12°, or −18°; stricter darkness reduces available windows |
| Advance reminder | 5 minutes | 1–60 minutes before the viewing window |
| Emit advance reminders | On | Enables button 1 for real upcoming windows |
| Reminder start / end hour | 0 / 24 | Hub local time; start inclusive, end exclusive; 22 / 6 spans midnight; equal hours mean all day |
| Orbital refresh interval | 6 hours | 2, 4, 6, or 12 hours; checked during hourly maintenance |
| Maximum orbital age | 72 hours | 24, 48, 72, 120, or 168 hours; applies to the predicted time as well as the current time |
| Prediction sampling | 30 seconds | 15 or 30 seconds; 15 uses about twice as much calculation work |
| Maximum retained windows | 10 | 1–30; calculation stops when this count is reached |
| Current position | Off | Publishes azimuth, elevation, and range approximately once a minute |
| Debug logging | Off | Request and calculation summaries; turns off after 30 minutes |

Start with the defaults. If you get no windows, first check `dataStatus` and `predictionStatus`. A working driver can correctly report no opportunities. For a bright satellite such as the ISS, relaxing the darkness limit to −3° or lowering the peak filter may reveal additional windows. Increasing the horizon cannot extend beyond the orbital-age limit.

## Automations

The device exposes three **Pushable Button** events:

| Button | Event |
| --- | --- |
| 1 | Advance reminder. Also emitted by **Test Reminder**. |
| 2 | A qualifying viewing window has begun. |
| 3 | A previously active viewing window has ended. |

An easy Rule Machine setup:

1. Create a rule triggered by **Skywatch ISS → button 1 pushed**.
2. Set a local string variable from this device's `notificationText` attribute.
3. Send that variable to your chosen notification device, or use it in a spoken announcement.
4. Press **Test Reminder** on Skywatch. The message begins `TEST:` and `reminderType` is `test`; real reminders use `advance`.

For a simple fixed announcement, button 1 can trigger “A satellite viewing window is coming up.” Button 2 can dim a patio light, and button 3 can restore it. Configure those actions in your own rule.

Allowed reminder hours and the reminder-enable preference apply to automatic **button 1** events only. Buttons 2 and 3 still operate. Test Reminder intentionally bypasses those restrictions. It does not change `passState` or invent a forecast.

Alternatively, trigger on custom attribute `passState` becoming `visible`. Use `dataStatus` and `predictionStatus` when displaying availability. Overlapping recalculations suppress duplicate reminder/start events. Events missed while the hub is offline are not replayed; an end event is emitted only for a window observed as active, within two minutes of its end.

## Dashboard and attribute reference

Use an **Attribute** tile for `passSummary`, `nextPassStart`, `minutesUntilPass`, or `passState`. `upcomingPasses` is a newline-separated text list, not JSON.

| Attributes | Meaning |
| --- | --- |
| `satelliteName`, `catalogNumber` | Identity of the cached satellite |
| `passState` | `unknown` (unavailable/calculating), `none`, `upcoming`, `approaching`, or `visible` |
| `dataStatus` | `unavailable` (no elements), `fresh`, `cached` (usable older data or downloads paused), or `stale` (not usable for the selection/time) |
| `downloadStatus`, `predictionStatus`, `lastError` | Download/calculation progress and most recent error |
| `orbitalEpoch`, `orbitalAgeHours` | Element epoch in UTC and its age now |
| `lastUpdated` | Last successful element download, in hub local time |
| `predictionsThrough` | End of the computed interval; may be shortened by age or retained-window limits |
| `passCount` | Remaining qualifying windows, including one currently active |
| `nextPassStart`, `nextPassEnd` | Local date/time and zone; refer to the active window while visible |
| `nextPassStartEpoch`, `nextPassEndEpoch` | Unix seconds; zero when no window is available |
| `minutesUntilPass` | Rounded-up minutes; zero during the window, −1 when unavailable |
| `maximumElevation`, `visibleDuration` | Peak elevation in degrees and visible duration in seconds; zero without a window |
| `startDirection`, `endDirection` | 16-point compass directions for the visible segment |
| `passSummary`, `upcomingPasses` | Human-readable forecast text |
| `notificationText`, `reminderType` | Last reminder message and `advance` / `test`; retained after the event |
| `pushed`, `numberOfButtons` | Standard Hubitat button attributes; three buttons |
| `azimuth`, `elevation`, `rangeKm` | Optional current position, in degrees/degrees/km; elevation may be negative |
| `positionStatus`, `positionTime` | `current`, `disabled`, or `unavailable`, with the last current reading time |

Position numbers are retained when updates stop: use them only when `positionStatus` is `current`. Cleared string attributes may appear as blank or `null` in Hubitat. The catalog/name describe the cache; after changing satellites, wait for usable data before relying on them.

## Commands and provider policy

| Command | Behavior |
| --- | --- |
| Refresh | Downloads only when due and permitted; otherwise recalculates from cache |
| Recalculate | Rebuilds viewing windows from cached elements; no network request |
| Initialize | Restarts schedules and rebuilds the forecast; preserves the cache and request gate |
| Resume Downloads | Clears a download pause and tries again subject to the same request gate |
| Test Reminder | Emits a clearly marked test message and button 1 |
| Push | Manually emits button 1, 2, or 3; does not generate new reminder text |

CelesTrak asks clients to cache results, avoid polling faster than once every two hours, and stop automated requests after errors. Skywatch enforces a persisted **two-hour minimum between attempts per device**, even after preference changes, restarts, or manual commands. Normal downloads default to six hours. Hourly maintenance rebuilds the local forecast; minute checks update state and countdowns. Prediction work runs in small scheduled batches.

An HTTP error, timeout, or invalid response pauses downloads. Cached elements remain available until their age limit. Check `lastError`, resolve the network/catalog/provider issue, then press **Resume Downloads**. Repeatedly pressing Refresh will not bypass the limit. After changing satellites shortly after a request, the new satellite may have to wait for that same gate.

Provider details: [GP JSON documentation](https://celestrak.org/NORAD/documentation/gp-data-formats.php) and [usage policy](https://celestrak.org/usage-policy.php). The only query values sent are the satellite catalog number and JSON format. As with any HTTPS request, CelesTrak receives your public IP address and the driver's user-agent.

## Accuracy and supported orbits

Skywatch uses the near-Earth branch of **SGP4**, adapted from satellite.js, with WGS-72 propagation constants and a WGS-84 observer. The Sun calculation is a low-precision ephemeris; shadow checks conservatively exclude partial eclipses. Boundaries are refined to approximately one second after sampling, but that is numerical resolution, not a promise of real-world timing accuracy.

- Orbital periods of **225 minutes or more are rejected**. GPS, geostationary, and other deep-space satellites require a different propagation branch.
- Short/grazing windows near the sampling interval can be missed. Use 15-second sampling when investigating marginal passes.
- Fresh elements matter, especially after maneuvers. A seven-day age allowance is less reliable than the default three days.
- Earth-orientation corrections, atmospheric refraction/extinction, local terrain, brightness, weather, and spacecraft attitude are not modeled.
- A window cut off by the forecast horizon is omitted until a later calculation can determine its end.
- Hub schedules can run late under load. This driver is for casual observing and home automation, not precision tracking or navigation.

## Development and tests

The driver is a single Groovy file compatible with the Hubitat runtime. Tests run separately on a computer; no test dependencies are installed on the hub.

With Groovy 2.4.21 installed, run from this directory:

```sh
groovy tests/SkywatchCelesTrakTest.groovy
```

The suite checks 158 published SGP4 position/velocity vectors, independent Skyfield/JPL viewing-window fixtures at five locations, invalid elements, persistence, request throttling, stale callbacks, download pauses, reminder hours, duplicate suppression, event boundaries, and stale position status. See [tests/README.md](tests/README.md) for reference provenance and tolerances.

For issue reports, include driver version, hub firmware, satellite catalog number, preference values, and the relevant error text. Remove location coordinates and other private hub information before posting logs or state dumps.

## License and acknowledgments

MIT; see [LICENSE](LICENSE). SGP4 portions retain the satellite.js copyright and MIT notice in the driver itself; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Thanks to CelesTrak for public orbital data and SGP4 reference material. This is an independent community driver, not an official CelesTrak or Hubitat product.
