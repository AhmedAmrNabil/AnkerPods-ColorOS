# Context & Architectural Directive: Soundcore Liberty 4 NC (A3947) State Gating & Case Battery Fix

The current build produces severe UI flickering, disappearing views, and unstable battery bars. This is caused by three race conditions:

1. **Uninitialized `-1` Emissions:** Emitting uninitialized or `-1` battery values across IPC to `com.android.bluetooth` or `com.heytap.*` causes ColorOS to treat the device as invalid/disconnected, destroying and recreating the UI.
2. **Case Battery 0xFF Trap:** Soundcore earbuds return `0xFF` (255) for the case battery whenever the earbuds are out of the case. Forwarding `255` breaks ColorOS layout validation.
3. **Stale/Phantom Charging Bits:** The case charging bit can freeze or rapidly oscillate when buds transition from the case to the ears, causing ColorOS to loop between standard battery pills and animated lightning bolt drawables.

We need to resolve this by strictly decoupling the charging case from the readiness gate and implementing a caching state machine.

---

### Core Rules & Invariants

#### 1. Decoupled Readiness Gating

- **`isReady` depends strictly on Left and Right earbuds.** As soon as valid percentages (`0 <= level <= 100`) for both Left and Right buds are parsed from the `01 01` response, set `isReady = true`.
- **The charging case must NEVER block `isReady`.** If buds are pulled out before the Bluetooth handshake completes (the fast-draw scenario), the module must still transition to `isReady = true` immediately.
- **Dispatch Gate:** All hooks in `com.android.bluetooth`, `com.heytap.accessory`, and `com.heytap.mydevices` must return default system values or no-op until `isReady == true`. Never emit `-1` or uninitialized payloads to system IPC.

#### 2. Optional & Cached Case Logic

- **If `rawCase <= 100`:** The case is open and pogo-pin communication is active.
  - Cache the value: `cachedCase = rawCase`.
  - Update case charging flag from the packet: `isCaseCharging = (chargingByte & MASK_CASE) != 0`.
  - Return `rawCase`.
- **If `rawCase == 255 || rawCase > 100`:** The buds are in-ear and the case is unreachable.
  - Return `cachedCase ?: 100` (use the last known valid reading; if none exists yet, default to `100` to prevent layout collapse).
  - **Force `isCaseCharging = false`** (an unreachable case in your pocket or desk must never show an active charging animation).
- **On Disconnect (`onDisconnect`):**
  - Reset `isReady = false`.
  - Clear `cachedCase = null`.
  - Reset all charging flags: `isLeftCharging = false`, `isRightCharging = false`, `isCaseCharging = false`.

#### 3. Earbud Charging Sanity

- Parse Left and Right charging flags directly from the packet bitmask while in the case.
- When buds are actively connected and in use over A2DP, ensure charging states reflect their active status rather than floating bits.

---

### Implementation Instructions

1. **Refactor `SoundcoreStateManager`:**
   Implement the battery resolution and case caching logic cleanly:

   ```kotlin
   object SoundcoreStateManager {
       var isReady: Boolean = false
           private set

       var leftBattery: Int = 100
           private set
       var rightBattery: Int = 100
           private set
       var isLeftCharging: Boolean = false
           private set
       var isRightCharging: Boolean = false
           private set

       private var cachedCase: Int? = null
       val caseBattery: Int
           get() = cachedCase ?: 100

       var isCaseCharging: Boolean = false
           private set

       var ancMode: Int = 0 // 0: Normal, 1: ANC, 2: Transparency

       fun updateFromPacket(packet: ByteArray): Boolean {
           val rawL = packet[OFFSET_LEFT].toInt() and 0xFF
           val rawR = packet[OFFSET_RIGHT].toInt() and 0xFF
           val rawCase = packet[OFFSET_CASE].toInt() and 0xFF
           val chargingByte = packet[OFFSET_CHARGING].toInt() and 0xFF

           // 1. Mandatory Gate: L & R must be valid
           if (rawL in 0..100 && rawR in 0..100) {
               leftBattery = rawL
               rightBattery = rawR
               isLeftCharging = (chargingByte and MASK_CHARGING_LEFT) != 0
               isRightCharging = (chargingByte and MASK_CHARGING_RIGHT) != 0
               isReady = true
           } else {
               return false
           }

           // 2. Opportunistic Case Handling
           if (rawCase <= 100) {
               cachedCase = rawCase
               isCaseCharging = (chargingByte and MASK_CHARGING_CASE) != 0
           } else {
               // Out of case (0xFF) -> preserve cache, force charging off
               isCaseCharging = false
           }

           return true
       }

       fun onDisconnect() {
           isReady = false
           cachedCase = null
           isLeftCharging = false
           isRightCharging = false
           isCaseCharging = false
           leftBattery = 100
           rightBattery = 100
       }
   }
   ```
