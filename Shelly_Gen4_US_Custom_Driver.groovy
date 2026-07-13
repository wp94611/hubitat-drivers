/**
 *  Shelly Dimmer Gen4 US - Zigbee Driver for Hubitat
 *
 *  Version history:
 *    1.0.0  2026-07-01  Initial release — on/off working, setLevel broken (firmware bug)
 *    1.1.0  2026-07-01  Switched to zigbee.command() API, fixed delay protocol errors
 *    1.2.0  2026-07-02  Added ZCL r8 options fields, confirmed firmware fix needed
 *    2.0.0  2026-07-06  Firmware 2.0.0-beta3 fixes Zigbee Level Control — fully functional
 *                       Fixed voltage divisor (10→100), power monitoring rounding,
 *                       level transition debounce, null cluster guard
 *    2.0.1  2026-07-06  Fixed BigDecimal.round() → setScale() (MissingMethodException),
 *                       fixed firmware version ZCL Character String decoding
 *    2.0.2  2026-07-06  Fixed firmware string odd-length padding (Hubitat strips leading zero)
 *    2.0.3  2026-07-06  Fixed firmware parsing for both raw hex and pre-decoded string cases
 *    2.0.4  2026-07-06  Suppress ZDP bind response noise (0xnull cluster log spam)
 *    2.0.5  2026-07-07  Fixed health check cron for non-divisor-of-60 intervals (e.g. 90 min)
 *    2.0.6  2026-07-07  Reduced Zigbee mesh flooding: raised power monitoring minReportTime
 *                       5s→60s, level minReportTime 0→3s, energy 60s→300s
 *    2.0.7  2026-07-08  Fixed health check cron (.min closure not supported in sandbox),
 *                       increased health check timeout 60s→120s to prevent false offline
 *    2.0.8  2026-07-11  Added toggle() command (cluster 0x0006, cmd 0x02) — Shelly's own
 *                       2.0.0-beta1 changelog lists "Light: Fix toggle command via Zigbee",
 *                       carried forward into beta3, so this is vendor-confirmed working,
 *                       not just ZCL-spec inference. Untested on this specific unit — verify
 *                       physical light responds before wiring into Rule Machine.
 *    2.0.9  2026-07-11  Fixed toggle() not appearing as a UI/Rule Machine command — needed
 *                       an explicit "command \"toggle\"" declaration in metadata since Toggle
 *                       isn't a standard Hubitat capability. Method itself was correct in 2.0.8;
 *                       it just wasn't exposed anywhere invokable.
 *    2.1.0  2026-07-13  Added leaveAllGroups() — cluster 0x0004 (Groups), cmd 0x04 (Remove All
 *                       Groups). Fixes accidental Sunricher remote direct-bind: experimentation
 *                       with the remote's Find-and-Bind gesture left this device as a member of
 *                       more than one Zigbee group, so it was responding to multiple remote
 *                       zone buttons directly (bypassing Hubitat/Rule Machine entirely — no log
 *                       entries, because group-address traffic between remote and light doesn't
 *                       route through the coordinator). This command clears ALL group
 *                       memberships in one shot; standard ZCL command, no manufacturer-specific
 *                       payload needed. Does not affect Rule Machine control, which uses
 *                       unicast device commands unrelated to group membership.
 *
 *  Confirmed fingerprint: manufacturer="Shelly", model="Dimmer US"
 *  Minimum firmware: 2.0.0-beta3 (build 20260701-130241/g794ffdf)
 */

metadata {
    definition(
        name:      "Shelly Dimmer Gen4 US (Zigbee)",
        namespace: "community",
        author:    "Warren / Claude",
        importUrl: "https://raw.githubusercontent.com/wp94611/hubitat-drivers/main/Shelly_Gen4_US_Custom_Driver.groovy"
    ) {
        attribute "driverVersion", "string"
        capability "Actuator"
        capability "Switch"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "Refresh"
        capability "Configuration"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "HealthCheck"

        // "Toggle" isn't a real Hubitat capability, so on/off/setLevel show up automatically
        // via their capabilities above, but toggle() needs to be declared explicitly here to
        // appear as a UI command button and as a Rule Machine action target.
        command "toggle"
        command "leaveAllGroups"

        attribute "healthStatus",    "string"
        attribute "powerFactor",     "number"
        attribute "amperage",        "number"
        attribute "voltage",         "number"
        attribute "firmwareVersion", "string"

        fingerprint profileId:"0104",
                    endpointId:"01",
                    inClusters:"0000,0003,0004,0005,0008,0006,0B04,0702",
                    outClusters:"0019",
                    manufacturer:"Shelly",
                    model:"Dimmer US",
                    deviceJoinName:"Shelly Dimmer Gen4 US"
    }

    preferences {
        input name: "transitionTime",      type: "number",  title: "Dim transition time (tenths of a second, 0-65534)", defaultValue: 4,   range: "0..65534"
        input name: "minLevel",            type: "number",  title: "Minimum dim level (1-10%)",                         defaultValue: 1,   range: "1..10"
        input name: "maxLevel",            type: "number",  title: "Maximum dim level (90-100%)",                       defaultValue: 100, range: "90..100"
        input name: "levelReportDelta",    type: "number",  title: "Level reporting change threshold (1-25%)",          defaultValue: 5,   range: "1..25"
        input name: "powerReportDelta",    type: "number",  title: "Power reporting change threshold (watts)",          defaultValue: 5,   range: "1..100"
        input name: "enablePowerMonitor",  type: "bool",    title: "Enable power monitoring",                           defaultValue: true
        input name: "healthCheckInterval", type: "number",  title: "Health check interval (minutes, 0=disabled)",       defaultValue: 30,  range: "0..1440"
        input name: "logEnable",           type: "bool",    title: "Enable debug logging",                              defaultValue: true
        input name: "traceEnable",         type: "bool",    title: "Enable trace logging (verbose)",                    defaultValue: false
    }
}

// ── Lifecycle ──────────────────────────────────────────────────────────────

def installed() {
    log.info "${device.displayName}: installed()"
    sendEvent(name:"healthStatus", value:"unknown")
    sendEvent(name:"driverVersion", value:"2.1.0")
}

def updated() {
    log.info "${device.displayName}: updated() — driver v2.1.0"
    sendEvent(name:"driverVersion", value:"2.1.0")
    if (logEnable) runIn(1800, "disableDebugLogging")
    if (healthCheckInterval && healthCheckInterval > 0) {
        // Cron minute-field only supports divisors of 60: 1,2,3,4,5,6,10,12,15,20,30,60
        // Map any interval to the nearest valid value explicitly — no closures (not supported in sandbox)
        int mins = healthCheckInterval.toInteger()
        int cronMins
        if      (mins <= 1)  cronMins = 1
        else if (mins <= 2)  cronMins = 2
        else if (mins <= 3)  cronMins = 3
        else if (mins <= 4)  cronMins = 4
        else if (mins <= 5)  cronMins = 5
        else if (mins <= 6)  cronMins = 6
        else if (mins <= 10) cronMins = 10
        else if (mins <= 12) cronMins = 12
        else if (mins <= 15) cronMins = 15
        else if (mins <= 20) cronMins = 20
        else if (mins <= 30) cronMins = 30
        else                 cronMins = 60
        schedule("0 */${cronMins} * ? * *", "healthCheck")
        if (logEnable) log.debug "${device.displayName}: health check every ${cronMins} min"
    }
}

def configure() {
    log.info "${device.displayName}: configure()"
    List<String> cmds = []
    // On/off: report immediately on change, max every 10 min
    cmds += zigbee.onOffConfig(0, 600)

    // Level: minReport=3s prevents burst flooding during dim transitions
    // Without this, every step of a 10-second fade generates a Zigbee message
    int levelDelta = Math.round(((levelReportDelta ?: 5) * 2.54)).intValue()
    cmds += zigbee.configureReporting(0x0008, 0x0000, DataType.UINT8, 3, 600, levelDelta)

    if (enablePowerMonitor) {
        int powerDelta = ((powerReportDelta ?: 5) * 10).intValue()
        // Power monitoring: raised minReportTime from 5s → 60s to reduce mesh flooding
        // A kitchen dimmer's voltage/current doesn't need sub-minute resolution
        // reportableChange thresholds also raised to suppress minor noise reports
        cmds += zigbee.configureReporting(0x0B04, 0x050B, DataType.INT16,  60, 3600, powerDelta) // active power
        cmds += zigbee.configureReporting(0x0B04, 0x0505, DataType.UINT16, 60, 3600, 200)        // voltage (2V delta)
        cmds += zigbee.configureReporting(0x0B04, 0x0508, DataType.UINT16, 60, 3600, 100)        // current (0.1A delta)
        cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 300, 3600, 100)       // energy (every 5 min)
    }
    sendZigbeeCommands(cmds)
    runIn(2, "refresh")
}

// ── Commands ───────────────────────────────────────────────────────────────

def on() {
    log.info "${device.displayName}: on()"
    sendZigbeeCommands(zigbee.on())
}

def off() {
    log.info "${device.displayName}: off()"
    sendZigbeeCommands(zigbee.off())
}

def toggle() {
    log.info "${device.displayName}: toggle()"
    // Cluster 0x0006 (On/Off), Command 0x02 = Toggle — mandatory ZCL command alongside On/Off,
    // confirmed fixed by Shelly for Zigbee Light components in firmware 2.0.0-beta1 (see changelog above)
    sendZigbeeCommands(zigbee.command(0x0006, 0x02, [:]))
}

def leaveAllGroups() {
    log.info "${device.displayName}: leaveAllGroups() — clearing all Zigbee group memberships"
    // Cluster 0x0004 (Groups), Command 0x04 = Remove All Groups. Standard ZCL command,
    // no payload needed. Clears every group this device belongs to in one shot, regardless
    // of how many or which ones — safer than removing specific group IDs one at a time when
    // the exact membership list isn't known.
    sendZigbeeCommands(zigbee.command(0x0004, 0x04, [:]))
}

def setLevel(level, rate = null) {
    log.info "${device.displayName}: setLevel(${level}, ${rate}) called"

    int lvl = Math.max((minLevel ?: 1).intValue(), Math.min((maxLevel ?: 100).intValue(), level.toInteger()))
    int zigbeeLevel = Math.round(lvl * 2.54).intValue()
    int tt = (rate != null) ? Math.round(rate.toInteger() * 10).intValue() : (transitionTime ?: 4).intValue()

    log.info "${device.displayName}: zigbeeLevel=${zigbeeLevel} tt=${tt}"

    // zigbee.command() — correct Hubitat API, avoids raw he cmd disconnection
    // Cluster 0x0008, Command 0x04 = Move to Level (with On/Off)
    // Payload bytes: level | tt low | tt high | options mask | options override
    String levelHex = Integer.toHexString(zigbeeLevel).padLeft(2, "0").toUpperCase()
    String ttLow    = Integer.toHexString(tt & 0xFF).padLeft(2, "0").toUpperCase()
    String ttHigh   = Integer.toHexString((tt >> 8) & 0xFF).padLeft(2, "0").toUpperCase()

    List<String> cmds = zigbee.command(0x0008, 0x04, [:], levelHex, ttLow, ttHigh, "00", "00")
    log.info "${device.displayName}: setLevel cmds → ${cmds}"
    sendZigbeeCommands(cmds)
}

def startLevelChange(String direction) {
    log.info "${device.displayName}: startLevelChange(${direction})"
    // Command 0x01 = Move, mode: 0x00=up 0x01=down, rate=50 units/sec, options mask+override
    String modeHex = (direction == "up") ? "00" : "01"
    List<String> cmds = zigbee.command(0x0008, 0x01, [:], modeHex, "32", "00", "00")
    sendZigbeeCommands(cmds)
}

def stopLevelChange() {
    log.info "${device.displayName}: stopLevelChange()"
    String cmd = "he cmd 0x${device.deviceNetworkId} 0x01 0x0008 0x03 {}"
    sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZIGBEE))
}

def refresh() {
    log.info "${device.displayName}: refresh()"
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0006, 0x0000)
    cmds += zigbee.readAttribute(0x0008, 0x0000)
    if (enablePowerMonitor) {
        cmds += zigbee.readAttribute(0x0B04, 0x050B)
        cmds += zigbee.readAttribute(0x0B04, 0x0505)
        cmds += zigbee.readAttribute(0x0B04, 0x0508)
        cmds += zigbee.readAttribute(0x0702, 0x0000)
    }
    cmds += zigbee.readAttribute(0x0000, 0x4000)
    sendZigbeeCommands(cmds)
}

def ping() {
    log.info "${device.displayName}: ping()"
    sendZigbeeCommands(zigbee.readAttribute(0x0006, 0x0000))
}

// ── Zigbee message parsing ─────────────────────────────────────────────────

def parse(String description) {
    if (traceEnable) log.trace "${device.displayName}: parse() → ${description}"
    updateHealthStatus("online")

    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (!descMap) return

    if (descMap.clusterInt == null) return

    switch (descMap.clusterInt) {
        case 0x0006: parseOnOff(descMap);                break
        case 0x0008: parseLevel(descMap);                break
        case 0x0B04: parseElectricalMeasurement(descMap); break
        case 0x0702: parseMetering(descMap);             break
        case 0x0000: parseBasic(descMap);                break
        default:
            // Suppress ZDP response clusters (0x8000+) — Bind/Unbind responses from coordinator
            // These populate clusterInt but leave descMap.cluster null, hence "0xnull" in logs
            if (traceEnable && descMap.clusterInt < 0x8000) {
                log.trace "${device.displayName}: unhandled cluster 0x${String.format('%04X', descMap.clusterInt)}"
            }
    }
}

// ── Cluster parsers ────────────────────────────────────────────────────────

private void parseOnOff(Map descMap) {
    if (descMap.attrInt != 0x0000) return
    String value = descMap.value == "01" ? "on" : "off"
    if (logEnable) log.debug "${device.displayName}: switch → ${value}"
    sendEvent(name:"switch", value:value, descriptionText:"${device.displayName} switch is ${value}")
}

private void parseLevel(Map descMap) {
    if (descMap.attrInt != 0x0000) return
    if (descMap.value == null) return
    int raw = Integer.parseInt(descMap.value, 16)
    int pct = Math.round(raw / 2.54).intValue()
    pct = Math.max(0, Math.min(100, pct))
    // Suppress transient zero-level reports during ramp transitions
    // to avoid spurious switch off events while device is still on
    if (pct == 0 && device.currentValue("switch") == "on") {
        if (traceEnable) log.trace "${device.displayName}: suppressing transient level 0 during transition"
        return
    }
    if (logEnable) log.debug "${device.displayName}: level → ${pct}%"
    sendEvent(name:"level", value:pct, unit:"%", descriptionText:"${device.displayName} level is ${pct}%")
    if (pct > 0 && device.currentValue("switch") == "off") {
        sendEvent(name:"switch", value:"on")
    }
}

private void parseElectricalMeasurement(Map descMap) {
    if (descMap.value == null) return
    int raw = Integer.parseInt(descMap.value, 16)
    switch (descMap.attrInt) {
        case 0x050B:
            BigDecimal watts = (raw / 10.0).setScale(1, java.math.RoundingMode.HALF_UP)
            if (logEnable) log.debug "${device.displayName}: power → ${watts}W"
            sendEvent(name:"power", value:watts, unit:"W")
            break
        case 0x0505:
            BigDecimal volts = (raw / 100.0).setScale(1, java.math.RoundingMode.HALF_UP)
            if (logEnable) log.debug "${device.displayName}: voltage → ${volts}V"
            sendEvent(name:"voltage", value:volts, unit:"V")
            break
        case 0x0508:
            BigDecimal amps = (raw / 1000.0).setScale(3, java.math.RoundingMode.HALF_UP)
            if (logEnable) log.debug "${device.displayName}: current → ${amps}A"
            sendEvent(name:"amperage", value:amps, unit:"A")
            break
        case 0x0600:
            BigDecimal pf = raw / 100.0
            if (logEnable) log.debug "${device.displayName}: powerFactor → ${pf}"
            sendEvent(name:"powerFactor", value:pf)
            break
    }
}

private void parseMetering(Map descMap) {
    if (descMap.attrInt != 0x0000) return
    if (descMap.value == null) return
    long raw = Long.parseLong(descMap.value, 16)
    BigDecimal kwh = (raw / 1000.0).setScale(3, java.math.RoundingMode.HALF_UP)
    if (logEnable) log.debug "${device.displayName}: energy → ${kwh}kWh"
    sendEvent(name:"energy", value:kwh, unit:"kWh")
}

private void parseBasic(Map descMap) {
    if (descMap.attrInt == 0x4000 && descMap.value) {
        try {
            String val = descMap.value
            String fw

            if (val.matches("[0-9a-fA-F]+")) {
                // Raw hex string — Hubitat returned unparsed bytes
                // Pad to even length, decode, skip ZCL length prefix byte, keep printable ASCII
                String hex = val.length() % 2 != 0 ? "0" + val : val
                byte[] bytes = hex.decodeHex()
                fw = bytes.drop(1).findAll { it >= 0x20 && it <= 0x7E }
                         .collect { (char)(it & 0xFF) }.join("")
            } else {
                // Hubitat already decoded the ZCL Character String for us
                fw = val.replaceAll(/[^ -~]/, "")
            }

            if (fw) {
                if (logEnable) log.debug "${device.displayName}: firmware → ${fw}"
                sendEvent(name:"firmwareVersion", value:fw)
            }
        } catch (Exception e) {
            if (logEnable) log.debug "${device.displayName}: firmware parse error: ${e.message}"
        }
    }
}

// ── Health check ───────────────────────────────────────────────────────────

def healthCheck() {
    if (logEnable) log.debug "${device.displayName}: healthCheck()"
    ping()
    runIn(120, "healthCheckTimeout")
}

def healthCheckTimeout() {
    log.warn "${device.displayName}: health check timeout — device may be offline"
    updateHealthStatus("offline")
}

private void updateHealthStatus(String status) {
    unschedule("healthCheckTimeout")
    if (device.currentValue("healthStatus") != status) {
        sendEvent(name:"healthStatus", value:status)
    }
}

// ── Utilities ──────────────────────────────────────────────────────────────

private void sendZigbeeCommands(List<String> cmds) {
    if (!cmds) return
    // Pass list + protocol directly — HubMultiAction handles delay strings automatically
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

def disableDebugLogging() {
    log.info "${device.displayName}: auto-disabling debug logging"
    device.updateSetting("logEnable", [value:"false", type:"bool"])
}