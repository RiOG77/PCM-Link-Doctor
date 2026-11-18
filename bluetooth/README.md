# PCM Linker Bluetooth Doctor for Cayenne PCM6

Standalone Android APK (`llc.riog.pcmlinkbt`) built in a **separate module** from the existing PCM Link Doctor USB/Android Auto project. The existing USB app is unchanged.

## Controls
- Select a paired Porsche from the local Bluetooth list.
- Manually discover nearby Classic Bluetooth devices (requires Nearby Devices permission).
- Request pairing through Android's standard system dialog for an unpaired device.
- Request fresh SDP service UUIDs to see whether the remote device advertises HFP Audio Gateway / A2DP services.
- Open Android's Bluetooth settings for system-controlled profile connection switches.
- Start read-only background capture of HFP, A2DP, ACL, bonding, pairing requests, SDP changes, and Bluetooth adapter state.
- Mark “PCM spinner” moment and export local JSON report.

## Hard limits (without root)
- Cannot force HFP connect/disconnect or set Bluetooth connection priority on recent Android; that requires privileged platform APIs or system UI.
- Can't read PCM6 internal logs, HCI packets or the definitive reason PCM rejected a call.
- SDP UUID advertised != profile actually established.
- Logs only the chosen phone-device pairing and public Android broadcast states. Not contacts, audio, phone numbers, calls or messages.
- No INTERNET permission, no PCM write operations, no factory resets.

## Build
`./gradlew :bluetooth:assembleDebug`
APK: `bluetooth/build/outputs/apk/debug/bluetooth-debug.apk`.
GitHub Actions workflow: `.github/workflows/bluetooth-apk.yml`, on the dedicated feature branch.
