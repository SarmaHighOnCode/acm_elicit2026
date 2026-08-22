# TASK B1 — Manifest, permissions, app skeleton

> Copy everything below the line into your agent. **This unblocks all other Track B work.**

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SafeHop is an offline mesh SOS relay. When cell towers collapse in a flood or earthquake, phones
relay 24-byte emergency beacons to each other over Bluetooth LE until one reaches a device with
connectivity. Residual battery drives routing decisions.

Read first: `docs/ARCHITECTURE.md`, `docs/POWER.md`.

Modules: `core/` (pure Kotlin/JVM protocol — already implemented and compiling), `sim/`,
`app/` (**this task** — currently does not build).

**Current failure, verified:**
```
:app:processDebugMainManifest — app/src/main/AndroidManifest.xml does not exist
```
AGP configuration is otherwise healthy; 26 tasks run before it stops.

**Hard constraints**
- **AGP 9 has BUILT-IN Kotlin support.** Do **not** apply `org.jetbrains.kotlin.android`. Do
  **not** use `kotlinOptions {}` — it no longer exists. `app/build.gradle.kts` is already correct;
  **do not change it** except to add a dependency you were told to add.
- Do not change versions in `gradle/libs.versions.toml`.
- No new third-party dependencies.
- A fresh clone needs `local.properties` with `sdk.dir=<android-sdk-path>`, gitignored.
- Do not claim the build passes without running it. Paste real output.

**Toolchain:** AGP 9.3.0 · Gradle 9.5.0 · JDK 17 · Kotlin 2.3.21 · compileSdk 37 · minSdk 26 ·
Compose BOM 2026.08.00 · `namespace = "com.setu.mesh.app"` · `applicationId = "com.setu.mesh"`.

## Task

Make `:app` build, install, and launch to a placeholder screen, with all the permissions and the
foreground service SafeHop needs. No Bluetooth code in this task — just the shell.

## Files you may create

```
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
app/src/main/res/values/themes.xml
app/src/main/res/xml/backup_rules.xml
app/src/main/res/xml/data_extraction_rules.xml
app/src/main/kotlin/com/setu/mesh/app/MainActivity.kt
app/src/main/kotlin/com/setu/mesh/app/ui/theme/Theme.kt
app/src/main/kotlin/com/setu/mesh/app/service/SetuService.kt
app/src/main/kotlin/com/setu/mesh/app/PermissionGate.kt
```

**Do NOT touch** `core/`, `sim/`, or any `build.gradle.kts`.

## Manifest requirements

```xml
<!-- Android 12+ (API 31+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- API <= 30 -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />

<!-- GPS position for the SOS payload -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

`neverForLocation` on `BLUETOOTH_SCAN` is deliberate — it avoids needing location permission *for
scanning*. GPS is requested separately, for the SOS position. Do not remove that flag.

Service declaration:
```xml
<service
    android:name=".service.SetuService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

## `SetuService`

- extends `androidx.lifecycle.LifecycleService` (dependency already present)
- `startForeground` with type `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`
- notification channel with `IMPORTANCE_LOW`, ongoing, not dismissable
- notification text is a placeholder for now — later it shows power tier and carried-message count
- `START_STICKY`
- owns a `CoroutineScope` cancelled in `onDestroy`

**No Bluetooth code in this task.** The service should start, show its notification, and idle.

## `PermissionGate`

A composable that requests, in order, and shows which are still missing:
1. `POST_NOTIFICATIONS` (API 33+ only)
2. `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (API 31+ only)
3. `ACCESS_FINE_LOCATION`

Then a button that starts `SetuService`. Guard every API-level-specific request with a
`Build.VERSION.SDK_INT` check — requesting a permission that does not exist on the running API
level throws.

Also check and surface, without crashing:
- Bluetooth adapter present
- Bluetooth enabled (prompt, do not silently enable)
- `bluetoothLeAdvertiser != null` — **null on some devices, meaning that phone can never
  advertise.** Better to find that out now than during the demo.

## `MainActivity` + theme

`ComponentActivity` with `setContent { }`. Dark theme, near-black background (`#0A0A0B`) with
amber/red accents — the SOS UI is designed for darkness, and on OLED it draws less power, which
ties the UI to the project's energy thesis. Material 3.

For now: show `PermissionGate`, then a placeholder "SafeHop running" screen.

## Acceptance

```bash
./gradlew :app:assembleDebug
```

Then on a real device (an emulator cannot do BLE, but it can verify the app launches):
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Manual check: launch, grant all permissions, tap start, confirm the foreground notification
appears and survives backgrounding the app.

## Definition of done

- [ ] `./gradlew :app:assembleDebug` succeeds, output pasted
- [ ] APK installs on a physical device
- [ ] all permissions granted through the in-app flow without a crash
- [ ] foreground notification appears and persists when the app is backgrounded
- [ ] app reports clearly if `bluetoothLeAdvertiser` is null rather than crashing
- [ ] `app/build.gradle.kts` unchanged
- [ ] no `org.jetbrains.kotlin.android` plugin anywhere, no `kotlinOptions {}`
- [ ] `core/` and `sim/` untouched
