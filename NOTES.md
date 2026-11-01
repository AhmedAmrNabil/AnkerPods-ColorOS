# Porting Notes: HuaweiPods-ColorOS to Soundcore (A3947)

## Phase -1: Flake Environment & Baseline Build Verification

- **Environment**: NixOS flake dev shell with JDK 21, Android SDK platform version 37, build-tools 37.0.0, and AAPT2 override from maven.
- **Verification Commands Executed**:
  - `nix develop -c ./gradlew :app:assembleDebug`:
    - Result: `BUILD SUCCESSFUL in 8s` (38 actionable tasks: 12 executed, 26 up-to-date).
  - `nix develop -c ./gradlew :app:testDebugUnitTest`:
    - Result: `BUILD SUCCESSFUL in 14s` (26 actionable tasks: 15 executed, 11 up-to-date).
- **Status**: Baseline compiles cleanly without modifications.

---

## Phase 0: Reconnaissance Findings

### 1. Huawei Backend Architecture in HuaweiPods-ColorOS

#### A. Hook Points & Process Boundaries
- **`com.android.bluetooth`** (System Bluetooth Process):
  - `HeadsetStateDispatcher`: Hooks `com.android.bluetooth.a2dp.A2dpService` connection state callbacks to track connected Bluetooth audio devices. When a supported device connects or disconnects, it orchestrates lifecycle and controller sessions. Also handles app-facing broadcast commands (`ACTION_PODS_UI_INIT`, `ACTION_REFRESH_STATUS`, `ACTION_CONNECT_POD_REQUEST`, `ACTION_DEVICE_ROUTE_PROBE_REQUEST`).
  - `HuaweiHfpController`: The central engine managing AT commands, RFCOMM channels, and L2CAP connections for Huawei earbuds. Maintains session state, queries battery / ANC, receives responses, and updates:
    - System battery bridge: Calls `ColorOsSystemBatteryBridge.publish(context, device, battery, inEar)` to register battery levels directly into ColorOS `IOplusRemoteDevice` / `OplusBluetoothDevice` system channels.
    - Notification / Fluid Cloud: Triggers `MiBluetoothToastHook` (via `MiuiStrongToastUtil`).
    - Cross-process broadcasts: Emits `ACTION_PODS_BATTERY_CHANGED`, `ACTION_PODS_ANC_CHANGED`, `ACTION_PODS_CONNECTED`, `ACTION_PODS_DISCONNECTED`.
  - `MiBluetoothToastHook`: Builds the ColorOS earbud status notification with live battery icons and an action intent for cycling ANC (`ACTION_CYCLE_ANC`).
- **`com.heytap.mydevices`** (ColorOS "My Devices"):
  - `ColorOsMyDevicesHook`: Hooks the stock Bluetooth detail page (legacy ViewBinding in ColorOS 16.1 or Preference hierarchy in ColorOS 17). Rewrites rows/preferences to show battery summaries and an ANC mode toggle bar (Off / ANC / Transparency). Sends `ACTION_ANC_SELECT` and listens for `ACTION_PODS_ANC_CHANGED` / `ACTION_PODS_BATTERY_CHANGED`.
- **`com.heytap.accessory` & `com.oplus.wirelesssettings`**:
  - Intercept popup card and quick device settings.
- **Module App Process (`moe.chenxy.huaweipods`)**:
  - `MainUI` / `PopupActivity` / Compose UI: Consumes the broadcast state (`ACTION_PODS_BATTERY_CHANGED`, `ACTION_PODS_ANC_CHANGED`) and sends broadcast intents (`ACTION_ANC_SELECT`, `ACTION_CYCLE_ANC`, `ACTION_CONNECT_POD_REQUEST`).

#### B. Narrowest Consumed Interface
The entire hook ecosystem and UI interact with the earbud transport through a very clean contract:
1. **Device Identification**: Mac address + device name (detecting if it's our device).
2. **Connection Control**: `connect(context, device)`, `disconnect()`.
3. **State Observation**:
   - Connection state: `isConnecting`, `isConnected`.
   - Battery state: Left (`battery: Int`, `isCharging: Boolean`), Right, Case.
   - ANC mode: `OFF`, `NOISE_CANCELLATION`, `TRANSPARENCY`.
4. **ANC Control**:
   - `setAncMode(mode: NoiseControlMode)`
   - `cycleAncMode()`
5. **System Export**:
   - Supplying `BatteryParams` to `ColorOsSystemBatteryBridge.publish(context, device, battery)`.

---

### 2. OpenSCQ30 Architecture & Protocol Spec for Soundcore Liberty 4 NC (A3947)

From inspecting `OpenSCQ30/lib/src/devices/soundcore/a3947.rs` and its modules:

#### A. Transport & RFCOMM Connection
- **Socket**: Bluetooth RFCOMM socket.
- **Service UUID Resolution**:
  - Checks bonded device SDP UUIDs for vendor UUID matching prefix `0cf12d31-fac3-4553-bd80-d6832e700000` (mask `ffffffff-ffff-ffff-ffff-fffffff00000`).
  - Fallback to standard SerialPort UUID: `00001101-0000-1000-8000-00805f9b34fb`.

#### B. Packet Framing & Checksum
- **Framing**:
  - Outbound (Host -> Earbud): Header `[0x08, 0xee, 0x00, 0x00, 0x00]` (5 bytes) + Command (2 bytes) + Length (2 bytes, little-endian u16) + Body (`length - 10` bytes) + Checksum (1 byte).
  - Inbound (Earbud -> Host): Header `[0x09, 0xff, 0x00, 0x00, 0x01]` (5 bytes) + Command (2 bytes) + Length (2 bytes, little-endian u16) + Body (`length - 10` bytes) + Checksum (1 byte).
- **Checksum Calculation**:
  - Sum of all preceding packet bytes modulo 256 (`u8` wrapping addition).

#### C. Packets & Commands for A3947
1. **Request State**:
   - Outbound Command: `[0x01, 0x01]`, empty body. Total packet:
     `0x08, 0xee, 0x00, 0x00, 0x00, 0x01, 0x01, 0x0a, 0x00, 0x02`.
2. **State Update Response / Push**:
   - Inbound Command: `[0x01, 0x01]`.
   - Body offsets (verified against test fixture in `OpenSCQ30/lib/src/devices/soundcore/a3947.rs`):
     - `body[0..1]`: TWS Status (`host_device`, `is_connected`)
     - `body[2]`: Left battery level (0..5, 255 = disconnected)
     - `body[3]`: Right battery level (0..5, 255 = disconnected)
     - `body[4]`: Left charging (1 = charging, 0 = not charging)
     - `body[5]`: Right charging (1 = charging, 0 = not charging)
     - `body[6..15]`: Dual firmware version (10 bytes)
     - `body[16..31]`: Serial number (16 ASCII bytes)
     - `body[32..36]`: Unknown version (5 bytes)
     - ... (EQ, hear_id, button configuration, ambient sound mode cycle)
     - `body[119]`: `ambient_sound_mode` (0 = NoiseCanceling, 1 = Transparency, 2 = Normal)
     - `body[120]`: `(manual_nc << 4) | adaptive_nc`
     - `body[121]`: `transparency_mode`
     - `body[122]`: `noise_canceling_mode`
     - `body[123]`: `wind_noise`
     - `body[124]`: `environment_detection`
     - `body[125]`: `transportation_mode`
     - `body[126..131]`: 6 unknown bytes
     - `body[132]`: Case battery level (0..5, 255 = unavailable / out of case)
3. **Asynchronous Inbound Updates**:
   - Dual Battery Level: Command `[0x01, 0x03]` -> `body[0]` = left level, `body[1]` = right level.
   - Dual Battery Charging: Command `[0x01, 0x04]` -> `body[0]` = left charging, `body[1]` = right charging.
   - Sound Modes Update: Command `[0x06, 0x01]` -> `body[0]` = ambient sound mode (0 = ANC, 1 = Transparency, 2 = Normal).
4. **Set Sound Modes**:
   - Outbound Command: `[0x06, 0x81]` with 7-byte body:
     `[ambient_sound_mode, (manual << 4) | adaptive, transparency_mode, noise_canceling_mode, wind_noise, env_detection, transport_mode]`.
   - ANC: `[0x00, 0x50, 0x01, 0x00, 0x01, 0x01, 0x03]`
   - Transparency: `[0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00]`
   - Normal: `[0x02, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00]`

---

### 3. Interface Design: `EarbudBackend`

```kotlin
package moe.chenxy.huaweipods.backend

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams

data class EarbudState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val battery: BatteryParams? = null,
    val ancMode: NoiseControlMode = NoiseControlMode.UNKNOWN,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
)

interface EarbudBackend {
    val state: StateFlow<EarbudState>
    fun connect(context: Context, device: BluetoothDevice)
    fun disconnect()
    fun setAncMode(mode: NoiseControlMode)
    fun cycleAncMode()
}
```

---

## Phase 1: Implementation Strategy Decision

### Decision: Option B (Pure Kotlin Protocol Port)

**Rationale**:
1. **Toolchain simplicity**: Option A requires the Android NDK, Rust compiler, cargo-ndk, and UniFFI bindings in the Nix dev environment. Currently, `flake.nix` explicitly sets `includeNDK = false;`. Adding full NDK and cargo-ndk cross-compilation is heavy, slow, and adds maintenance complexity.
2. **Minimal packet surface**: The protocol for A3947 (Liberty 4 NC) for the required MVP scope (battery L/R/case and 3-way ANC switching) consists of only 3 outbound packets (Request State, Set Sound Modes, ACK) and 4 inbound packets (State Update, Battery Level, Battery Charging, Sound Modes).
3. **Robustness & Verification**: Porting these cleanly into Kotlin takes ~200 lines, compiles in seconds, and can be 100% verified against byte fixtures from OpenSCQ30's unit tests.
4. Per `PLAN.md`: *"Default to B if the model's needed packets are small and A's toolchain is painful. Otherwise use A. Do not mix them."* Option B strictly meets this criterion.
