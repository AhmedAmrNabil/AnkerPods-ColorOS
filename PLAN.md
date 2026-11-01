# Task: Port HuaweiPods-ColorOS to Soundcore earbuds using OpenSCQ30

## Context
- CWD contains two repos:
  - `./HuaweiPods-ColorOS`: an LSPosed module (Kotlin, Gradle) for ColorOS 16/17.
    It speaks the Huawei earbud protocol itself and hooks ColorOS system
    components (`com.android.bluetooth`, `com.heytap.mydevices`,
    `com.heytap.accessory`, optional `com.oplus.wirelesssettings`) so the earbuds
    get: a connect popup, system battery, ANC toggles on the My Devices page,
    and a notification / fluid-cloud battery card.
  - `./OpenSCQ30`: a Rust library (`lib/`) with Soundcore protocol
    implementations per model, uniffi bindings (`uniffi-bindgen/`), and an
    Android app (`android/`, Kotlin + Compose) that already consumes the Rust
    lib. See `docs/build-android.md` and `docs/development.md`.
- Target device: ColorOS 16, Android 16, LSPosed (API 101+).
- My earbuds: Soundcore Liberty 4 NC, model code A3947. It is listed as
  supported in OpenSCQ30's README. Find its device module under `OpenSCQ30/lib`
  and use it as the protocol reference. Do not use A3953 (Liberty 4); that is
  a different device.
- Both projects are GPL-3.0. Keep license headers and attribution, and add
  OpenSCQ30 to the credits.

## Goal
Replace the Huawei protocol/backend in HuaweiPods-ColorOS with Soundcore
support, so my Soundcore earbuds get the same ColorOS integration.

MVP scope (nothing else):
1. Connect to the earbuds over the Soundcore transport.
2. Battery: left, right, and case if the model reports it. Push it into the
   system earbud battery channel the module already uses.
3. ANC mode read + set: Normal / ANC / Transparency. Expose it on the My
   Devices page and in the notification.
4. A Quick Settings tile (`TileService`) that cycles ANC mode and shows the
   current mode.
5. A home screen widget (Jetpack Glance) with battery plus ANC buttons.

Non-goals: EQ, gestures, spatial audio, image/CDN logic, other Huawei models,
HyperOS code.

## Build environment (NixOS)

All build, test, lint, and tooling commands MUST run through the flake's dev
shell. Never call `./gradlew`, `adb`, or `jadx` directly on the host.

Pattern:

    nix develop -c ./gradlew :app:assembleDebug

For anything with pipes, redirects, or `&&`, wrap it:

    nix develop -c bash -c "./gradlew :app:testDebugUnitTest && ./gradlew :app:lintDebug"

Standard commands:
- Debug APK:        `nix develop -c ./gradlew :app:assembleDebug`
- Release APK:      `nix develop -c ./gradlew :app:assembleRelease`
- Unit tests:       `nix develop -c ./gradlew :app:testDebugUnitTest`
- Lint:             `nix develop -c ./gradlew :app:lintDebug`
- Full check:       `nix develop -c ./gradlew build`  (slow; runs lint and
                    tests, so use it only at phase boundaries)
- Decompile APK:    `nix develop -c jadx -d out/<name> <file>.apk`
- Capture script:   `nix develop -c ./capture-coloros-environment.sh`

Rules:
1. `flake.nix` must be tracked by git, or Nix won't see it. Run
   `git add flake.nix flake.lock` after creating or changing it.
2. Phase -1 (before Phase 0): create `flake.nix` from the one I provide, set
   `platformVersions` / `buildToolsVersions` and the `aapt2` override path
   to match `compileSdk` / `buildToolsVersion` in `app/build.gradle.kts`, then
   run `nix develop -c ./gradlew :app:assembleDebug` UNCHANGED on the original
   repo. This must succeed before any code is touched. Record the result
   in `NOTES.md`.
3. If the build fails with an AAPT2 or "Could not start dynamic executable"
   error, fix the `aapt2FromMavenOverride` path or version first. If it still
   fails, add an FHS devShell (`buildFHSEnv`) and report that change. Do not
   work around it by editing the Gradle files.
4. Pass Gradle flags after the task, e.g.
   `nix develop -c ./gradlew :app:assembleDebug --stacktrace`.
5. `adb` and installs: use the shell's `adb` (`nix develop -c adb ...`).
   Never install to the phone without telling me; I'll do on-device testing.
6. If a command is slow because Nix is re-evaluating, add `--offline` only when
   I say there's no network. Otherwise leave it out.

## Plan

### Phase 0: Recon (no code changes, write findings to `NOTES.md`)
1. Map the Huawei backend in HuaweiPods-ColorOS: where connection, packet
   build/parse, model detection, battery state, and ANC state live, and how
   the hooks in system processes talk to the app process (binder, broadcast,
   service). Identify the narrowest interface the hooks and UI consume.
2. Map OpenSCQ30: how the model is selected, how the transport connects,
   how battery and ANC state and setters are exposed, and what the Android app
   uses (uniffi API surface in Kotlin).
3. Write the interface the rest of the module will depend on, e.g.
   `EarbudBackend { connect(), disconnect(), state: Flow<EarbudState>,
   setAnc(mode) }` with `EarbudState` = battery L/R/case, charging flags,
   ANC mode, connected.

### Phase 1: Choose how to reuse OpenSCQ30 (decide, record the decision in NOTES.md)
- Option A: build OpenSCQ30's Rust lib with uniffi for Android (arm64-v8a
  only) and depend on the generated Kotlin bindings plus the .so.
  Pros: protocol correctness and future model support. Cons: cargo-ndk
  toolchain in the Gradle build.
- Option B: port only the packets needed for my model (battery, ANC get/set,
  checksum, framing) into plain Kotlin, using OpenSCQ30's Rust source as the
  spec. Pros: simple build. Cons: one model only.
- Default to B if the model's needed packets are small and A's toolchain is
  painful. Otherwise use A. Do not mix them.

### Phase 2: Introduce the abstraction
1. Put the Huawei logic behind `EarbudBackend` without changing behavior.
2. Build the debug APK and verify nothing regressed (tests pass).
   (Do this before deleting anything.)

### Phase 3: Soundcore backend
1. Implement `SoundcoreBackend : EarbudBackend` using the Phase 1 choice.
2. Device identification: make the module recognize my earbuds (by
   Bluetooth name/address prefix or user selection) and route them to
   `SoundcoreBackend`. Leave the Huawei backend compiled but disabled, or
   remove it only after everything works.
3. Add a minimal capability table for my model: has ANC, modes supported,
   has case battery. Map Soundcore ANC modes to the existing three-state
   Off/ANC/Transparency UI.
4. Keep the existing Bluetooth-process hooks that write the battery into
   ColorOS's system earbud battery channel, and feed them from
   `EarbudState`.

### Phase 4: ColorOS integration
1. My Devices detail page: show battery and the ANC switcher for the
   Soundcore device. If a Huawei-model-specific layout blocks this, add a
   generic layout for "battery + ANC".
2. Connect popup: use the built-in generic image if no official image exists.
3. Notification / fluid cloud: battery plus ANC.

### Phase 5: Tile and widget
1. `TileService`: tap cycles ANC mode, label shows current mode, state is
   Active when connected. It talks to the same app-side service, never
   holds its own socket.
2. Glance widget: battery L/R/case, three ANC buttons.
3. Handle process death: the tile and widget connect on demand through the
   service and show a "disconnected" state gracefully. Request
   `BLUETOOTH_CONNECT` where needed.

### Phase 6: Cleanup
Remove dead Huawei-only code (models, CDN/image fetching, capture tooling)
only after Phase 5 works on-device. Update README, app ID (use a new
one, e.g. `...soundcorepods.coloros`), credits, and license notes.

## Constraints
- Do not touch the LSPosed scope list beyond what already exists, except to
  add nothing new unless required (and say why).
- Never log Bluetooth addresses or account info.
- Keep changes in small commits, one per phase.
- Add unit tests for packet parsing (battery, ANC) using byte fixtures from
  OpenSCQ30's own tests where possible.
- If a private-API hook breaks on my My Devices version, report the exact
  class/method that changed rather than guessing.

## Acceptance (I'll verify on-device; list these in your final message)
1. Earbuds connect; battery appears in the status bar / Bluetooth settings.
2. ANC toggles from My Devices and the notification, and the earbuds react.
3. The QS tile cycles modes and reflects changes made on the earbuds.
4. The widget shows live battery and works after the app is killed.
5. `./gradlew :app:assembleRelease` and unit tests pass.

## Reporting
At the end, give: what works, what is stubbed, which hooks may be fragile
across ColorOS versions, and the manual test steps for the above.