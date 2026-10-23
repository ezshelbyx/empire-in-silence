# Security & Privacy — Empire in Silence

## Permission inventory (spec §12: never request unnecessary permissions)

| Permission | Why it exists | Abuse potential |
|---|---|---|
| `QUERY_ALL_PACKAGES` | Full installed-package inventory is the core function (debloat classification needs the complete picture, including system apps) | Broad visibility into installed apps |
| `PACKAGE_USAGE_STATS` | Standby-bucket/usage diagnostics — **user must still grant Usage Access manually** in system settings; we never prompt silently | Historical usage data |
| `ACCESS_NETWORK_STATE` | Read-only connectivity state for network diagnostics (does **not** allow any network I/O) | None meaningful |

**Not requested (by design):**

- `INTERNET` — the app performs **zero network I/O**. No update checks, no
  telemetry, no crash reporting, no remote config. This structurally prevents
  exfiltration of package lists/logs and makes "remote content triggers a
  privileged command" impossible: there is no remote content.
- `REQUEST_INSTALL_PACKAGES`, `DELETE_PACKAGES`, `WRITE_SETTINGS`,
  `RECEIVE_BOOT_COMPLETED`, `SYSTEM_ALERT_WINDOW`, accessibility, VPN — none
  needed for the implemented feature set.

## Threat model

| Threat | Mitigation |
|---|---|
| Command injection via package name/path/value | Strict validation (`Validation.kt`) + command template allowlist + runner-level second allowlist. Injection attempt strings are unit-tested (`ValidationTest`, `CommandTemplatesTest`). |
| Destructive system change | Critical block list (telephony, SystemUI, launchers, package/permission managers, Play services, security, connectivity, boot path) — **hard-blocked before UI selection**; `DebloatEngine.plan()` re-checks. |
| Mass debloating by accident | Batch >1 requires explicit confirmation token; single-at-a-time execution; dry-run preview of exact commands; journal + undo per entry. |
| Malicious root command | Root commands come only from `CommandTemplates` (narrow regex allowlist). No free-form root API. No module downloads/installs in code at all. |
| SELinux / verified boot / thermal protection tampering | Sysfs write allowlist explicitly rejects panic/sysrq/modprobe proc paths; thermal throttling disable is never implemented; EIS does not modify boot images or partitions. |
| Bypassing Shizuku/root authorization | `Shizuku.requestPermission()` is the official prompt; root authorization goes through the installed root manager. No pre-grant, no cached credentials, no hardcoded su passwords (none exist in Android). |
| Device-policy / carrier / financed devices | EIS never queries or modifies DevicePolicyManager admin state and never attempts to remove device-admin or financing protections (spec §5). |
| Data exfiltration | No INTERNET permission + no analytics SDKs + journal stays in `filesDir`; exports are clipboard-only and user-initiated. |
| Supply-chain in privileged path | Dependencies limited to Shizuku (official artifacts), libsu, Compose/AndroidX, kotlinx-serialization. No arbitrary script execution; research/downloads are structurally separate from command execution (there are no downloads). |

## Secure implementation notes

- All commands shown to the user before/during execution (dry-run output and
  journal entries contain the exact command string) — nothing runs hidden.
- `OpResult` carries real exit codes and stderr; failures are never mapped to
  success. Verification read-back failures are reported as failures.
- Backup JSON contains journal entries only — **no device identifiers**,
  serials, account data or installed-app lists (spec §10).
- ProGuard keeps only binder entry points (ShizukuProvider, UserService,
  AIDL stubs) — see `app/proguard-rules.pro`.
- Release signing keys are excluded via `.gitignore`; never committed.

## Privacy policy (short form, for About screen / distribution)

1. **Data collection:** none. No accounts, identifiers, usage telemetry, or
   crash reports are collected or transmitted.
2. **Network:** the app has no network permission and cannot transmit data.
3. **Local storage:** a change journal (commands you ran and their results)
   is stored in private app storage on your device. It is deleted when you
   clear app data, and never leaves the device unless *you* export it.
4. **Permissions:** package visibility (core feature), optional usage access
   (diagnostics you grant manually), network state (read-only).
5. **Remote content:** none exists; remote content can never trigger commands.
6. **Contact/removal:** deleting the app removes all its local data; nothing
   is retained elsewhere.

## Reporting

Found an issue? The failure report in Safety Center produces a local text
report (failed commands + messages) you can share manually. No automatic
collection exists.
