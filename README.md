# hubitat-drivers

Custom Hubitat drivers for Snake Road House. Currently: one driver, for the
Shelly Dimmer Gen4 US running in Zigbee mode.

## Shelly_Gen4_US_Custom_Driver.groovy

**Current version: 2.1.0** (2026-07-13)

Adds commands the stock/generic Hubitat Zigbee drivers don't provide for
this device:

| Command | What it does |
|---|---|
| `toggle()` | Cluster `0x0006` (On/Off), cmd `0x02` (Toggle). Vendor-confirmed working per Shelly's own 2.0.0-beta1 changelog ("Light: Fix toggle command via Zigbee"). |
| `leaveAllGroups()` | Cluster `0x0004` (Groups), cmd `0x04` (Remove All Groups). Clears every Zigbee group membership on the device in one shot — fixes a device responding directly to remote button presses (bypassing Hubitat entirely) after accidental Find-and-Bind binding. |

Also includes: on/off, dimming with configurable min/max level and
transition time, power/energy monitoring, and an active health check.

### Minimum firmware

Shelly firmware **2.0.0-beta3** (build `20260701-130241/g794ffdf`) or later.
Earlier betas have broken Zigbee Level Control.

### Installing on Hubitat

**First time:**
1. Hubitat → **Drivers Code** → **+ Add Driver**
2. Paste in the full contents of `Shelly_Gen4_US_Custom_Driver.groovy`
3. **Save**
4. Assign the driver to the device (device page → **Type** → select "Shelly Dimmer Gen4 US (Zigbee)")

**Updating after a change here:**
1. Hubitat → **Drivers Code** → open "Shelly Dimmer Gen4 US (Zigbee)"
2. **⋮ → Import** — the URL should auto-fill from the driver's `importUrl` field
3. Confirm the overwrite prompt → **Save**
4. On each device using this driver: **Preferences → Save Preferences** — this forces Hubitat's `updated()` to actually run on that device instance and confirms the version number bumped

### Why the filename has no version number

The raw GitHub URL Hubitat uses to fetch this file is tied to the filename,
not the content. Keeping the filename constant (`Shelly_Gen4_US_Custom_Driver.groovy`,
no `_v2.1.0` suffix) means the same `importUrl` keeps working across every
future push — only the file's *contents* need to change, never the link
Hubitat points at.

### Full version history

See the changelog comment block at the top of the driver file itself —
every version from 1.0.0 through 2.1.0, with reasoning for each fix.
