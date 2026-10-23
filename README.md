# Empire in Silence (EIS)

**Silent Authority. Maximum Control.**

An Android system optimization utility built around one principle: **stability before
aggressive tweaking, evidence before claims, user control before automation.**

- Package: `com.eis.silentoptimizer`
- Category: Android System Optimization
- Stack: Kotlin 2.0, Jetpack Compose, AGP 8.7, minSdk 26 (Android 8.0), target/compileSdk 35 (Android 14/15)
- UI: dark charcoal/silver/muted-gold tactical theme, 8 sections, no decorative animation

## What it does

| Section | Capability |
|---|---|
| **Dashboard** | Device identity, privilege status (Standard/Shizuku/Root), RAM/storage, battery/thermal, recent actions, evidence-based recommendations |
| **Debloat** | Package scan → classify (Recommended / Optional / User / Core / **Critical-Blocked** / Unknown) → search/filter → select → **dry-run** → disable → verify → journal. Evidence + risk + reversal per package. Restore plan for disabled packages. Exportable report. |
| **Gaming** | Game detection (category API + heuristics), 5 profiles (Balanced/Competitive/Max Stability/Battery Saver/Custom), display refresh policy with read-back verification, honest capability matrix (never claims FPS or per-app Game Mode it cannot deliver) |
| **Daily** | Animation scales, refresh-rate policy, battery/thermal diagnostics, storage guidance, background/standby guidance. Standard mode degrades to read-only + guidance. |
| **Root Lab** | Read-first sysfs inspection: CPU governors/frequencies, GPU devfreq, thermal zones, zRAM, I/O schedulers, kernel info, **module inventory** (Magisk/KernelSU/APatch). Single-node validated writes with dry-run, read-back verification and journaled undo. Never touches thermal throttling, SELinux, verified boot or panic paths. |
| **Device Intelligence** | Full read-only device profile + recommendation priorities |
| **Safety Center** | Full change journal, per-entry **undo** (dry-run capable), exportable backup (clipboard), failure report, recovery guidance, non-reversible warnings |
| **Settings** | Safety defaults, Shizuku/root setup guides, about, privacy statement |

## Privilege modes

| Mode | Requirement | What unlocks |
|---|---|---|
| **A - Standard** | nothing | Device intelligence, inventory, diagnostics, guidance |
| **B - Shizuku** | [Shizuku](https://shizuku.rikka.app/) running + authorized (wireless debugging or ADB; **restart after every reboot**) | Package disable/enable (UserService), settings reads/writes, `pm` queries |
| **C - Root** | Magisk / KernelSU / APatch with su granted to EIS | Everything above through root + sysfs reads/writes + module inventory |

The highest **usable** tier is chosen at runtime: `ROOT > SHIZUKU > STANDARD`.
Root is detected by an actual `su` probe (uid=0), never by a manager app being installed.

## Build

### Prerequisites

- JDK 17+ (tested with Temurin 21)
- Android SDK: `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`
- ~4 GB free disk for Gradle/AGP caches

### Debug APK

```bash
# point Gradle at your SDK (or let Android Studio create local.properties)
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

### Unit tests

```bash
./gradlew :app:testDebugUnitTest
# report: app/build/reports/tests/testDebugUnitTest/index.html
```

Tests cover: input validation & command allowlisting (injection attempts),
package classification & the critical block list, planning/restore plans,
dry-run purity, batch confirmation guard, journal recording, **rollback/undo**,
privilege-mode composition, and recommendation compatibility rules.

### Release APK

Release builds are **unsigned by default** — no keys live in this repository
(see `.gitignore` and [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md)).

```bash
./gradlew :app:assembleRelease
# output: app/build/outputs/apk/release/app-release-unsigned.apk
# then sign locally per docs/RELEASE_SIGNING.md
```

## Documentation

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, data flow, design decisions |
| [docs/SUPPORTED_FEATURES.md](docs/SUPPORTED_FEATURES.md) | Android versions + feature/privilege matrix |
| [docs/COMPATIBILITY_MATRIX.md](docs/COMPATIBILITY_MATRIX.md) | Device/OEM coverage status |
| [docs/RESEARCH_BIBLIOGRAPHY.md](docs/RESEARCH_BIBLIOGRAPHY.md) | Sources with links (Shizuku, AOSP, UAD, XDA...) |
| [docs/SECURITY_AND_PRIVACY.md](docs/SECURITY_AND_PRIVACY.md) | Threat model, permissions, privacy policy |
| [docs/TESTING_REPORT.md](docs/TESTING_REPORT.md) | What was **actually tested** vs unverified |
| [docs/GUIDES.md](docs/GUIDES.md) | Install, Shizuku activation, root authorization, troubleshooting |
| [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) | Rebuilding a signed release APK |
| [docs/DEBLOAT_DATABASE.md](docs/DEBLOAT_DATABASE.md) | Evidence & risk methodology for the package DB |

## Safety model (short version)

1. **Validate** every package name / path / value against strict patterns; anything
   failing validation never reaches a shell.
2. **Allowlist** every command through known templates (`CommandTemplates`), with a
   second allowlist check inside the runners (defense in depth).
3. **Dry-run** previews the exact command before execution.
4. **Confirm** — batch actions require explicit confirmation; nothing runs silently.
5. **Execute** one command at a time (no mass shell lines).
6. **Verify** by reading state back; report honestly when Android rejected a change.
7. **Journal** previous state + undo command per entry.
8. **Undo** from Safety Center; non-reversible changes are labeled loudly.

Critical packages (telephony, SystemUI, launchers, package/permission management,
Play services, security, connectivity, boot path) are **hard-blocked** — they can
never be selected, regardless of any database entry or OEM profile.

## Privacy

- **No `INTERNET` permission.** The app performs zero network I/O by design.
- No analytics, no telemetry, no crash reporting, no device identifiers collected.
- Journal/backups are stored in app-private storage; export only via explicit
  user action (clipboard).
- Remote content cannot trigger privileged commands because there is no remote content.

## License / credits

Built on public documentation from the Android Open Source Project, the
[Shizuku](https://github.com/RikkaApps/Shizuku) project (RikkaApps),
[libsu](https://github.com/topjohnwu/libsu) (topjohnwu), and community debloat
research ([UAD](https://github.com/0x192/universal-android-debloater) and
[UAD-ng](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation)).
See [docs/RESEARCH_BIBLIOGRAPHY.md](docs/RESEARCH_BIBLIOGRAPHY.md).
