# Research Bibliography — Empire in Silence

Every external fact, library coordinate, and OEM behavior claim in this codebase is
traced to a primary source below. Nothing in the app's recommendation engine asserts a
benefit that is not anchored to one of these references. This file is the evidence
ledger for `RecommendationEngine`'s `evidence` / `evidenceUrl` fields and for the
compatibility notes shown in the Safety and Intel screens.

---

## Android platform behavior

| Claim in code | Source |
|---|---|
| `peak_refresh_rate` constrains display policy; panel rate ≠ game FPS | AOSP — [Multiple refresh rates](https://source.android.com/docs/core/graphics/multiple-refresh-rate) |
| Animation scale keys (`window_animation_scale`, `transition_animation_scale`, `animator_duration_scale`) live in the `global` settings namespace | AOSP — [Settings table](https://source.android.com/docs/core/architecture/configuration/tunables) and `Settings.Global` reference |
| `pm disable-user` / `pm uninstall --user 0` only affect the calling user; the package is not removed from the system image | AOSP — [PackageManager shell commands](https://source.android.com/docs/core/tests/debug/development#pm-commands) |
| `cmd package` / `settings` shell commands require `shell` UID or elevated privilege | AOSP — [adb shell](https://source.android.com/docs/core/tests/debug/adb) |
| Android 11+ package-visibility filtering; `QUERY_ALL_PACKAGES` permission model | Android Developers — [Package visibility filtering](https://developer.android.com/training/package-visibility) |
| Device-owner / profile-owner policies must not be bypassed | Android Developers — [Device administration](https://developer.android.com/guide/topics/admin/device-admin) |

## Privilege escalation integrations

| Integration | Source |
|---|---|
| Shizuku API surface (`Shizuku.pingBinder`, `Shizuku.checkSelfPermission`, `Shizuku.getRemoteProcess`) | Maven Central — `dev.rikka.shizuku:api:13.1.5`, [rikka Shizuku](https://github.com/RikkaApps/Shizuku) |
| Shizuku UserService binder protocol and pre-v11 permission semantics | Shizuku — [UserService documentation](https://github.com/RikkaApps/Shizuku/wiki#user-service) |
| libsu Shell API (`Shell.getShell`, `Shell.Result`, `Shell.su` / `Shell.sh`) | JitPack — `com.github.topjohnwu.libsu:core:6.0.0`, [topjohnwu/libsu](https://github.com/topjohnwu/libsu) |
| libsu package namespace is `com.topjohnwu.superuser` (NOT the JitPack `com.github.topjohnwu` group) | Verified by inspecting the published 6.0.0 AAR's `classes.jar` |

> **Build note:** libsu is not on Maven Central. `settings.gradle.kts` adds
> `maven("https://jitpack.io")` specifically for it. The Gradle *group* is
> `com.github.topjohnwu.libsu` but the *Kotlin/Java package* is
> `com.topjohnwu.superuser` — a common source of unresolved-reference errors.

## Community debloat lists (recommendation evidence only)

| Package set | Source |
|---|---|
| Known-safe-to-disable telemetry/analytics package names | Community gist — [mcxiaoke Android debloat list](https://gist.github.com/mcxiaoke/ade05718f590bcd574b807c4706a00b1) |
| Cross-checked against | [debloat-list](https://github.com/MuntashirAkon/android-debloat) and [universal android debloater](https://github.com/0x192/universal-android-debloater) |

EIS treats these lists as **advisory input to a recommendation**, never as an
instruction to execute. The user sees the package name, the evidence, and the reversal
path before anything runs, and every mutation is journaled (`docs/SECURITY_AND_PRIVACY.md`).

## Toolchain / build pins

| Component | Version | Rationale |
|---|---|---|
| AGP | 8.7.3 | Last 8.x with stable `buildFeatures.aidl` under Kotlin 2.0 |
| Kotlin | 2.0.21 | Compose compiler now ships in the Kotlin plugin (`kotlin.compose`) |
| compileSdk / targetSdk | 35 | Android 15 |
| minSdk | 26 | `LocalBinder` / modern `PackageManager` APIs; avoids legacy shims |
| JDK (build) | 21 (toolchain at `/c/eis-toolchain/jdk-21.0.12.1+1`) | AGP 8.x requires 17+; bytecode target set to 17 |
| Gradle | 8.9 | Matches AGP 8.7.3 compatibility matrix |
| Robolectric | 4.14.1 | Supports SDK 35 shadows for JVM-side inventory tests |

## AIDL / Shizuku UserService

`app/src/main/aidl/com/eis/silentoptimizer/privilege/IUserService.aidl` mirrors the
Shizuku UserService contract. AGP 8 disabled AIDL generation by default; it is
explicitly re-enabled with `android.buildFeatures.aidl = true`. The interface is kept
deliberately narrow — `exec(command)` and a `version()` probe — so the privileged
process exposes no capability beyond running a shell command the caller could not
already have run.

---

## Verification method

Claims above were confirmed against the live artifacts during the build, not from
memory:

1. Shizuku coordinates resolved from Maven Central search (`search.maven.org`).
2. libsu build resolved from the JitPack `api/builds` endpoint (tag `6.0.0` → SUCCESS).
3. libsu package name verified by unpacking the published AAR and listing
   `com/topjohnwu/superuser/**` classes.
4. Full `:app:compileDebugKotlin` / `:app:testDebugUnitTest` run against the pinned
   toolchain to prove the integrated APIs match their documented signatures.
