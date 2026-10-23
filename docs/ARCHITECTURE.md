# Architecture — Empire in Silence

## Overview

Single-module Android app (Kotlin + Jetpack Compose) with package-level module
separation. One Gradle module keeps the build simple and the APK small; internal
packages carry the modularity the spec requires (UI, detection, privileges,
Shizuku, root, inventory, debloat, performance, gaming, thermal, history,
backup, recommendations).

```
com.eis.silentoptimizer
├── EISApplication / MainActivity      # entry, wiring (manual DI)
├── AppContainer                       # owns journal, gates, runners, engines
├── core/
│   ├── Types.kt                       # PrivilegeMode, RiskLevel, OpResult, BlockReason
│   ├── Validation.kt                  # ALL input validation (pure, unit-tested)
│   └── Journal.kt                     # ChangeRecord + JournalStore interfaces
├── device/
│   └── DeviceProbe.kt                 # DeviceProfile, BatterySnapshot, ThermalSnapshot
├── privilege/
│   ├── PrivilegeDetector.kt           # pure mode composition ROOT > SHIZUKU > STANDARD
│   ├── ShizukuGate.kt                 # binder lifecycle, permission flow, UserService bind
│   ├── EisUserService.kt (+ AIDL)     # runs as shell/root: IPackageManager calls
│   └── RootGate.kt                    # libsu init, su probe, manager detect, module list
├── shell/
│   ├── CommandTemplates.kt            # the ONLY command builders + execution allowlist
│   ├── ShizukuCommandRunner.kt        # Shizuku.newProcess (short commands)
│   ├── RootCommandRunner.kt           # libsu (root) — includes uid=0 probe
│   └── (StandardCommandRunner)        # /proc reads only; everything else honest-fail
├── inventory/PackageInventory.kt      # PackageManager scan, enabled states
├── debloat/
│   ├── DebloatDatabase.kt             # evidence-based package profiles + critical block list
│   └── DebloatEngine.kt               # classify → plan → dry-run/execute → verify → journal
├── daily/DailyOptimizer.kt            # animation/refresh ops with read-back verification
├── gaming/                            # GameDetector, profiles, capability matrix
├── rootlab/RootLab.kt                 # read-first sysfs inspector + guarded writes
├── recommend/RecommendationEngine.kt  # device-aware, evidence/confidence tagged
├── safety/                            # JsonJournalStore, SafetyCenter (undo/backup/report)
└── ui/                                # ViewModel + 8 screens + theme + components
```

## Data flow

```
UI (Compose screen)
  └── EisViewModel (StateFlow: ui / debloat / gaming / rootLab)
        └── AppContainer
              ├── runner(): ROOT → Shizuku? → Standard
              │     └── CommandRunner.exec(cmd)
              │           1. CommandTemplates.isAllowedCommand(cmd)   ← last gate
              │           2. run under shell/root identity
              │           3. return OpResult{success, exitCode, stdout, stderr, message}
              ├── DebloatEngine / DailyOptimizer / GamingEngine / RootLab
              │     └── journal.record(ChangeRecord{prev, new, undo, success})
              ├── ShizukuGate (UserService → IPackageManager for enable/disable)
              └── SafetyCenter (history, undo, backup, failure report)
```

## Key design decisions

### 1. Two-layer command security (spec §12)

Commands exist only inside `CommandTemplates`. Every builder returns `null`
when its inputs fail `Validation` (package regex, settings-key/value regex,
sysfs subtree allowlist, refresh-rate whitelist, animation whitelist). The
runners apply a **second** check (`isAllowedCommand`) with regexes describing
the known command shapes, so even a future caller bypassing the templates
cannot execute arbitrary strings. No free-form command API exists anywhere in
the UI.

### 2. Privilege composition is pure

`PrivilegeDetector.composeState(...)` is a pure function over booleans — unit
tested without Android. Root requires an actual `su` probe returning `uid=0`;
a manager app being installed only produces an informational note.

### 3. UserService for package state, shell for the rest

`setApplicationEnabledSetting` is a hidden binder API. Per the official
Shizuku guide, we call it from a **UserService** (separate process with shell
identity where non-SDK restrictions don't apply), not via reflection from the
app process. Short read/`settings`/`pm` commands use `Shizuku.newProcess`.

### 4. Verification over assumptions

- Package disable: command exit code **plus** post-state read-back where
  available; failures reported verbatim.
- Refresh rate: `settings put` followed by `settings get` — if the OEM build
  ignores the key, EIS reports "no change in effect" instead of success.
- Sysfs writes: node must be readable first, single validated token, read-back
  after write, journaled undo of the previous value.

### 5. Journal-first safety (spec §10)

Every mutation records `ChangeRecord{action, target, previousValue, newValue,
mode, success, undoCommand}` to app-private JSON (500-entry cap for low-end
devices). Dry runs never journal. Undo re-executes the recorded undo command
through the active runner and journals the inverse; entries without an undo
command are explicitly labeled non-reversible.

### 6. Manual DI, no Hilt/KSP

`AppContainer` is created in `Application.onCreate`. Avoids annotation
processing (faster builds, fewer moving parts on low-memory dev machines) and
keeps the dependency graph to Compose + Shizuku + libsu + kotlinx-serialization.

### 7. UI structure

Single `Scaffold` with 8-item `NavigationBar` + `NavHost`. One `EisViewModel`
holds dashboard/debloat/gaming/rootLab state flows (screens are presentation
only). Compose Material3 with a custom dark-only palette; no motion beyond
defaults (spec §11 forbids decorative animation).

## Concurrency

- ViewModels run work in `viewModelScope`; blocking I/O (PackageManager scans,
  runner exec) on `Dispatchers.IO`/`Default`.
- Command timeout: 30 s (`withTimeoutOrNull`) for Shizuku processes.
- No background daemons; the app does nothing while closed (spec §3:
  usable on low-memory devices).

## Persistence

| Store | File | Contents |
|---|---|---|
| Journal | `filesDir/eis_journal.json` | ChangeRecord list (cap 500) |

Game profiles and animation preferences are *not* persisted as side effects —
settings live in Android's own settings tables (or are reverted); EIS records
every change in the journal instead of mirroring state.
