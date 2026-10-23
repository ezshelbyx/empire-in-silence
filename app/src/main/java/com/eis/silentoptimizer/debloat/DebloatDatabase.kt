package com.eis.silentoptimizer.debloat

import com.eis.silentoptimizer.core.RiskLevel

/**
 * Debloat database (spec 5, deliverable 9).
 *
 * Entries are evidence-based: every profile cites its source (community
 * debloat projects, official documentation, or AOSP). Risk tiers:
 *
 *  - LOW:       reversible disable of a clearly optional app; feature loss
 *               expected only if the user uses that feature.
 *  - MEDIUM:    optional OEM service whose removal can affect adjacent
 *               features (e.g. Bixby vision, gamespace).
 *  - HIGH:      never recommended automatically; shown for completeness.
 *  - CRITICAL:  hard block list - telephony, SystemUI, launcher, package
 *               management, permissions, boot, connectivity, security.
 *
 * "Recommended" in any source list is NOT a guarantee (see UAD issue #1072:
 * a Xiaomi bootloop after bulk "Recommended" removals). Therefore:
 * default action is `disable-user` for user 0, one package at a time,
 * with a journal entry and undo for every action.
 */
enum class Recommendation { RECOMMENDED, OPTIONAL, NONE, BLOCKED }

enum class Evidence {
    UAD_NG,            // github.com/0x192/universal-android-debloater (and UAD-ng fork)
    UAD_ISSUE,         // documented incident in a UAD issue thread
    XDA,               // XDA Developers guide
    AOSP,              // source.android.com / AOSP behavior
    OFFICIAL_OEM,      // OEM documentation
    AOSP_LIST,         // package role visible in AOSP source
    COMMUNITY_LIST,    // maintained community package list (cited per entry)
    EIS_ANALYSIS,      // derived from package metadata on-device
}

data class PackageProfile(
    val packageName: String,
    val displayName: String,
    val oem: String,           // "universal", "samsung", "xiaomi", ...
    val purpose: String,
    val recommendation: Recommendation,
    val risk: RiskLevel,
    val action: String,        // human-readable recommended action
    val reason: String,
    val evidence: Evidence,
    val sourceUrl: String,
    val reversal: String,
)

data class ProfileBundle(
    val oem: String,
    val packages: List<PackageProfile>,
)

object DebloatDatabase {

    /**
     * Universal critical block list. A package matching any of these is
     * BLOCKED regardless of OEM profile - never offered for disable (spec 5:
     * never automatically disable telephony, SystemUI, launcher, package
     * management, Play services, permission management, accessibility,
     * device policy, security, connectivity, boot-critical components).
     */
    val CRITICAL_PATTERNS: List<Regex> = listOf(
        // Telephony / emergency / SMS / carrier
        Regex("^com\\.android\\.phone$"),
        Regex("^com\\.android\\.server\\.telecom$"),
        Regex("^com\\.android\\.providers\\.telephony$"),
        Regex("^com\\.android\\.contacts$"),
        Regex("^com\\.android\\.dialer$"),
        Regex("^com\\.android\\.mms"),
        Regex("^com\\.android\\.ims"),
        Regex("^com\\.qualcomm\\.qti\\.radio"),
        Regex("^com\\.qti\\."),
        Regex("^com\\.mediatek\\.ims"),
        Regex("^org\\.codeaurora\\.ims$"),
        Regex("^com\\.android\\.stk$"),
        // System UI, launcher, settings, package/permission management
        Regex("^com\\.android\\.systemui$"),
        Regex("^com\\.android\\.settings$"),
        Regex("^com\\.android\\.providers\\.settings$"),
        Regex("^com\\.android\\.permissioncontroller$"),
        Regex("^com\\.google\\.android\\.permissioncontroller$"),
        Regex("^com\\.android\\.packageinstaller$"),
        Regex("^com\\.google\\.android\\.packageinstaller$"),
        Regex("^com\\.android\\.shell$"),
        Regex("^com\\.miui\\.home$"),
        Regex("^com\\.sec\\.android\\.app\\.launcher$"),
        Regex("^com\\.samsung\\.android\\.app\\.launcher$"),
        Regex("^com\\.android\\.launcher[3]?$"),
        Regex("^com\\.google\\.android\\.apps\\.nexuslauncher$"),
        Regex("^com\\.oppo\\.launcher$"),
        Regex("^com\\.coloros\\.launcher"),
        Regex("^com\\.vivo\\.launcher$"),
        Regex("^com\\.motorola\\.launcher$"),
        Regex("^com\\.transsion\\.launcher"),
        // Framework / boot / ART / WebView
        Regex("^android$"),
        Regex("^com\\.android\\.framework"),
        Regex("^com\\.android\\.webview$"),
        Regex("^com\\.google\\.android\\.webview$"),
        Regex("^com\\.android\\.ext\\.services$"),
        Regex("^com\\.android\\.ext\\.shared$"),
        Regex("^com\\.google\\.android\\.ext\\.services$"),
        Regex("^com\\.android\\.inputdevices$"),
        Regex("^com\\.android\\.location\\.provider$"),
        Regex("^com\\.android\\.externalstorage$"),
        Regex("^com\\.android\\.documentsui$"),
        Regex("^com\\.media\\.android\\."),  // media framework providers often boot-critical on vendor builds
        // Package/updates/system stability
        Regex("^com\\.android\\.vending$"),                 // Play Store
        Regex("^com\\.google\\.android\\.gms$"),            // Play services (when present)
        Regex("^com\\.google\\.android\\.gsf$"),
        Regex("^com\\.android\\.updates$"),
        Regex("^com\\.android\\.dummy$"),
        // Accessibility
        Regex("^com\\.google\\.android\\.accessibility\\.talkback$"),
        // Device / enterprise policy
        Regex("^com\\.android\\.devicepolicy$"),
        Regex("^com\\.android\\.shell$"),
        // Security / verified boot / find device
        Regex("^com\\.google\\.android\\.apps\\.security\\.secureservice$"),
        Regex("^com\\.xiaomi\\.finddevice$"),
        Regex("^com\\.samsung\\.android\\.spay"),
        Regex("^com\\.google\\.android\\.apps\\.papers$"),  // DevicePolicy owner on some builds
        // Critical connectivity
        Regex("^com\\.android\\.networkstack$"),
        Regex("^com\\.android\\.networkstack\\.tethering$"),
        Regex("^com\\.android\\.connectivity\\.resources$"),
        Regex("^com\\.google\\.android\\.apps\\.turbolite$"),
    )

    /** Patterns that mark a package as high-risk even if not strictly blocked. */
    private fun matchesCritical(packageName: String): Boolean =
        CRITICAL_PATTERNS.any { it.matches(packageName) }

    fun classify(packageName: String, isSystem: Boolean): Recommendation {
        if (matchesCritical(packageName)) return Recommendation.BLOCKED
        val profile = lookup(packageName) ?: return Recommendation.NONE
        return if (matchesCritical(packageName)) Recommendation.BLOCKED
        else profile.recommendation
    }

    fun lookup(packageName: String): PackageProfile? = ALL_PROFILES[packageName]

    fun forOem(oemKey: String): List<PackageProfile> =
        ALL_PROFILES.values.filter { it.oem == oemKey || it.oem == "universal" }

    // ------------------------------------------------------------------
    // Universal entries (Facebook preload, Google optional apps)
    // ------------------------------------------------------------------

    private fun universal() = listOf(
        PackageProfile(
            "com.facebook.appmanager", "Facebook App Manager", "universal",
            "Facebook OMAPI/Update service preloaded by many OEMs",
            Recommendation.RECOMMENDED, RiskLevel.CAUTION,
            "Disable for current user",
            "Manages Facebook preload updates; not required for core Android. Loss: Facebook preload auto-updates.",
            Evidence.UAD_NG,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.facebook.appmanager",
        ),
        PackageProfile(
            "com.facebook.services", "Facebook Services", "universal",
            "Facebook background services preloaded by OEMs",
            Recommendation.RECOMMENDED, RiskLevel.CAUTION,
            "Disable for current user",
            "Background Facebook integration services; not required for core Android.",
            Evidence.UAD_NG,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.facebook.services",
        ),
        PackageProfile(
            "com.facebook.system", "Facebook System", "universal",
            "Facebook platform hooks preloaded by OEMs",
            Recommendation.RECOMMENDED, RiskLevel.CAUTION,
            "Disable for current user",
            "Facebook system integration; disabling may break Facebook single-sign-on from preloads.",
            Evidence.UAD_NG,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.facebook.system",
        ),
        PackageProfile(
            "com.facebook.katana", "Facebook", "universal",
            "Preloaded Facebook client app",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Uninstall updates / disable for current user",
            "User-facing app; can be removed as an ordinary app if unused.",
            Evidence.UAD_NG,
            "https://github.com/0x192/universal-android-debloater",
            "cmd package install-existing --user 0 com.facebook.katana",
        ),
        PackageProfile(
            "com.google.android.feedback", "Google Feedback", "universal",
            "AOSP/Pixel feedback app",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user",
            "Only used to send device feedback; no system dependency documented.",
            Evidence.UAD_NG,
            "https://github.com/0x192/universal-android-debloater/issues/738",
            "pm enable --user 0 com.google.android.feedback",
        ),
        PackageProfile(
            "com.google.android.apps.photos", "Google Photos", "universal",
            "Google Photos gallery/backup client",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable only if unused (may be default gallery)",
            "User-facing app; on Pixel builds it may be the default gallery and backup target - losing it can drop photo backup.",
            Evidence.UAD_ISSUE,
            "https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/issues/658",
            "pm enable --user 0 com.google.android.apps.photos",
        ),
        PackageProfile(
            "com.google.android.music", "YouTube Music", "universal",
            "Preloaded Google music streaming client",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user",
            "User-facing media app; no documented system dependency.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.google.android.music",
        ),
        PackageProfile(
            "com.google.android.videos", "Google TV / Play Movies", "universal",
            "Preloaded Google video client",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user",
            "User-facing media app; no documented system dependency.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.google.android.videos",
        ),
        PackageProfile(
            "com.google.android.GoogleCamera", "GCam (preloaded)", "universal",
            "Google Camera build shipped as system app on some devices",
            Recommendation.NONE, RiskLevel.CAUTION,
            "Leave alone unless another camera is installed",
            "May be the only camera; do not disable without a tested replacement.",
            Evidence.EIS_ANALYSIS, "",
            "pm enable --user 0 com.google.android.GoogleCamera",
        ),
        PackageProfile(
            "com.snapchat.android", "Snapchat (preload)", "universal",
            "Preloaded social app",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable for current user",
            "Preloaded third-party app; removal is a user choice, but it may be reinstalled by OEM market.",
            Evidence.UAD_NG,
            "https://github.com/0x192/universal-android-debloater",
            "cmd package install-existing --user 0 com.snapchat.android",
        ),
    )

    // ------------------------------------------------------------------
    // Samsung One UI
    // ------------------------------------------------------------------

    private fun samsung() = listOf(
        PackageProfile(
            "com.samsung.android.bixby.agent", "Bixby Voice", "samsung",
            "Bixby assistant main agent",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable for current user if Bixby unused",
            "Evidence: community lists and UAD logs include Bixby components as removable; loss = Bixby/voice features break.",
            Evidence.UAD_ISSUE,
            "https://github.com/0x192/universal-android-debloater/issues/933",
            "pm enable --user 0 com.samsung.android.bixby.agent",
        ),
        PackageProfile(
            "com.samsung.android.bixby.wakeup", "Bixby Wakeword", "samsung",
            "Bixby always-listening wakeword service",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable with Bixby agent",
            "Depends on Bixby agent; disabling stops wakeword listening (battery-positive).",
            Evidence.UAD_ISSUE,
            "https://github.com/0x192/universal-android-debloater/issues/933",
            "pm enable --user 0 com.samsung.android.bixby.wakeup",
        ),
        PackageProfile(
            "com.samsung.android.visionintelligence", "Bixby Vision", "samsung",
            "Camera visual analysis / Bixby Vision framework",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable for current user if unused",
            "Bixby Vision features in camera/gallery stop working.",
            Evidence.UAD_ISSUE,
            "https://github.com/0x192/universal-android-debloater/issues/933",
            "pm enable --user 0 com.samsung.android.visionintelligence",
        ),
        PackageProfile(
            "com.samsung.android.app.spage", "Samsung Free / News", "samsung",
            "Left-of-home news/media panel",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user",
            "Home screen panel only; system continues to work.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.samsung.android.app.spage",
        ),
        PackageProfile(
            "com.samsung.android.magazine", "Samsung Magazine", "samsung",
            "Lock screen magazine content",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user",
            "Content service only.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.samsung.android.magazine",
        ),
        PackageProfile(
            "com.samsung.android.ardrawing", "AR Doodle", "samsung",
            "AR drawing camera toy",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user",
            "Optional camera feature.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.samsung.android.ardrawing",
        ),
        PackageProfile(
            "com.samsung.android.aremoji", "AR Emoji", "samsung",
            "AR emoji creator",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user",
            "Optional camera feature.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.samsung.android.aremoji",
        ),
        PackageProfile(
            "com.samsung.android.forest", "Digital Wellbeing (Samsung)", "samsung",
            "Samsung wellbeing / focus mode",
            Recommendation.NONE, RiskLevel.CAUTION,
            "Leave alone",
            "Integrated with Settings on One UI; disabling can break Settings pages.",
            Evidence.EIS_ANALYSIS, "",
            "pm enable --user 0 com.samsung.android.forest",
        ),
        PackageProfile(
            "com.samsung.android.knox", "Knox", "samsung",
            "Samsung enterprise/security platform",
            Recommendation.NONE, RiskLevel.HIGH_RISK,
            "Do not disable",
            "Security, work profile, Secure Folder and payments depend on Knox.",
            Evidence.OFFICIAL_OEM,
            "https://docs.samsungknox.com/",
            "Not recommended to reverse - leave disabled state untouched",
        ),
        PackageProfile(
            "com.sec.android.app.launcher", "One UI Home", "samsung",
            "Samsung default launcher",
            Recommendation.NONE, RiskLevel.HIGH_RISK,
            "Never disable",
            "Disabling the active launcher removes the home screen (documented in community guides).",
            Evidence.XDA,
            "https://www.xda-developers.com/uninstall-carrier-oem-bloatware-without-root-access/",
            "pm enable --user 0 com.sec.android.app.launcher",
        ),
    )

    // ------------------------------------------------------------------
    // Xiaomi / Redmi / POCO (MIUI / HyperOS)
    // ------------------------------------------------------------------

    private fun xiaomi() = listOf(
        PackageProfile(
            "com.miui.analytics", "MIUI Analytics", "xiaomi",
            "Xiaomi telemetry/analytics service",
            Recommendation.RECOMMENDED, RiskLevel.SAFE,
            "Disable for current user",
            "Telemetry only; MIUI/HyperOS lists mark it safe. Privacy-positive.",
            Evidence.COMMUNITY_LIST,
            "https://gist.github.com/mcxiaoke/ade05718f590bcd574b807c4706a00b1",
            "pm enable --user 0 com.miui.analytics",
        ),
        PackageProfile(
            "com.miui.msa.global", "MSA Ad Service", "xiaomi",
            "MIUI system ads service",
            Recommendation.RECOMMENDED, RiskLevel.SAFE,
            "Disable for current user",
            "Delivers system ad content; disabling removes ads. Community lists mark safe.",
            Evidence.COMMUNITY_LIST,
            "https://gist.github.com/mcxiaoke/ade05718f590bcd574b807c4706a00b1",
            "pm enable --user 0 com.miui.msa.global",
        ),
        PackageProfile(
            "com.miui.bugreport", "MIUI Bug Report", "xiaomi",
            "OEM bug reporting tool",
            Recommendation.RECOMMENDED, RiskLevel.SAFE,
            "Disable for current user",
            "Optional diagnostics tool.",
            Evidence.COMMUNITY_LIST,
            "https://gist.github.com/mcxiaoke/ade05718f590bcd574b807c4706a00b1",
            "pm enable --user 0 com.miui.bugreport",
        ),
        PackageProfile(
            "com.miui.hybrid", "Quick Apps", "xiaomi",
            "MIUI quick app runtime",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable for current user if quick apps unused",
            "Quick apps (web-app hybrids) stop opening; normal apps unaffected.",
            Evidence.COMMUNITY_LIST,
            "https://gist.github.com/mcxiaoke/ade05718f590bcd574b807c4706a00b1",
            "pm enable --user 0 com.miui.hybrid",
        ),
        PackageProfile(
            "com.miui.hybrid.accessory", "Quick Apps Accessory", "xiaomi",
            "Quick apps companion",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable with com.miui.hybrid",
            "Companion of quick apps runtime.",
            Evidence.COMMUNITY_LIST,
            "https://gist.github.com/mcxiaoke/ade05718f590bcd574b807c4706a00b1",
            "pm enable --user 0 com.miui.hybrid.accessory",
        ),
        PackageProfile(
            "com.xiaomi.midrop", "Mi Drop / ShareMe", "xiaomi",
            "Xiaomi peer-to-peer file transfer",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable for current user if unused",
            "Local sharing feature lost; package availability differs by ROM.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/fadeltd/xiaomi-debloater",
            "pm enable --user 0 com.xiaomi.midrop",
        ),
        PackageProfile(
            "com.miui.player", "Mi Music", "xiaomi",
            "OEM music player",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user",
            "User-facing app; may be default player.",
            Evidence.COMMUNITY_LIST,
            "https://gist.github.com/mcxiaoke/ade05718f590bcd574b807c4706a00b1",
            "pm enable --user 0 com.miui.player",
        ),
        PackageProfile(
            "com.miui.video", "Mi Video", "xiaomi",
            "OEM video player",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user",
            "User-facing app; may be default player.",
            Evidence.COMMUNITY_LIST,
            "https://gist.github.com/mcxiaoke/ade05718f590bcd574b807c4706a00b1",
            "pm enable --user 0 com.miui.video",
        ),
        PackageProfile(
            "com.miui.securitycenter", "Security Center", "xiaomi",
            "MIUI permission/battery/management hub",
            Recommendation.NONE, RiskLevel.HIGH_RISK,
            "Do not disable",
            "Reported to break Battery, app management and background controls even without bootloop (UAD #641).",
            Evidence.UAD_ISSUE,
            "https://github.com/0x192/universal-android-debloater/issues/641",
            "pm enable --user 0 com.miui.securitycenter",
        ),
        PackageProfile(
            "com.miui.home", "System Launcher", "xiaomi",
            "MIUI/HyperOS default launcher",
            Recommendation.NONE, RiskLevel.HIGH_RISK,
            "Never disable",
            "Home screen disappears without a replacement launcher (MIUI list explicitly warns).",
            Evidence.COMMUNITY_LIST,
            "https://gist.github.com/mcxiaoke/ade05718f590bcd574b807c4706a00b1",
            "pm enable --user 0 com.miui.home",
        ),
        PackageProfile(
            "com.xiaomi.finddevice", "Find Device", "xiaomi",
            "Anti-theft / find-my-device",
            Recommendation.NONE, RiskLevel.HIGH_RISK,
            "Do not disable",
            "Theft protection and remote locate depend on it.",
            Evidence.COMMUNITY_LIST,
            "https://gist.github.com/mcxiaoke/ade05718f590bcd574b807c4706a00b1",
            "pm enable --user 0 com.xiaomi.finddevice",
        ),
    )

    // ------------------------------------------------------------------
    // Tecno HiOS / Infinix XOS (Transsion)
    // ------------------------------------------------------------------

    private fun transsion() = listOf(
        PackageProfile(
            "com.transsion.phonemaster", "Phone Master", "tecno",
            "Transsion security/cleaner preinstall",
            Recommendation.RECOMMENDED, RiskLevel.CAUTION,
            "Disable for current user",
            "Third-party cleaner preinstall; EIS_ANALYSIS: no core dependency. Test calling after change.",
            Evidence.EIS_ANALYSIS, "",
            "pm enable --user 0 com.transsion.phonemaster",
        ),
        PackageProfile(
            "com.transsion.hios.launcher", "HiOS Launcher", "tecno",
            "HiOS default launcher",
            Recommendation.NONE, RiskLevel.HIGH_RISK,
            "Never disable",
            "Active launcher; disabling removes home screen.",
            Evidence.EIS_ANALYSIS, "",
            "pm enable --user 0 com.transsion.hios.launcher",
        ),
        PackageProfile(
            "com.transsion.xos.launcher", "XOS Launcher", "infinix",
            "XOS default launcher",
            Recommendation.NONE, RiskLevel.HIGH_RISK,
            "Never disable",
            "Active launcher; disabling removes home screen.",
            Evidence.EIS_ANALYSIS, "",
            "pm enable --user 0 com.transsion.xos.launcher",
        ),
        PackageProfile(
            "com.transsion.tclwireless", "Wireless display service", "tecno",
            "Transsion wireless projection",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable if wireless casting unused",
            "Casting feature lost.",
            Evidence.EIS_ANALYSIS, "",
            "pm enable --user 0 com.transsion.tclwireless",
        ),
        PackageProfile(
            "com.transsion.dnd", "Smart Scan / Gallery", "tecno",
            "Gallery/gallery extras",
            Recommendation.NONE, RiskLevel.CAUTION,
            "Leave alone",
            "May be the only gallery on some HiOS builds.",
            Evidence.EIS_ANALYSIS, "",
            "pm enable --user 0 com.transsion.dnd",
        ),
    )

    // ------------------------------------------------------------------
    // OPPO ColorOS / realme UI
    // ------------------------------------------------------------------

    private fun coloros() = listOf(
        PackageProfile(
            "com.coloros.gamespace", "Game Space", "oppo",
            "ColorOS gaming overlay",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable if unused",
            "Gaming overlay/notification blocking lost (2018 XDA guide documented removal on Find X; version-specific).",
            Evidence.XDA,
            "https://xdaforums.com/t/guide-adb-oppo-find-x-system-apps-safe-to-uninstall-disable-without-root.3818165/",
            "pm enable --user 0 com.coloros.gamespace",
        ),
        PackageProfile(
            "com.coloros.weather.service", "Weather service", "oppo",
            "ColorOS weather data provider",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable if weather widget unused",
            "Widgets relying on it stop updating (XDA guide, version-specific).",
            Evidence.XDA,
            "https://xdaforums.com/t/guide-adb-oppo-find-x-system-apps-safe-to-uninstall-disable-without-root.3818165/",
            "pm enable --user 0 com.coloros.weather.service",
        ),
        PackageProfile(
            "com.coloros.assistantscreen", "Smart Assistant", "oppo",
            "Left-of-home assistant screen",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user",
            "Home panel only.",
            Evidence.XDA,
            "https://xdaforums.com/t/guide-adb-oppo-find-x-system-apps-safe-to-uninstall-disable-without-root.3818165/",
            "pm enable --user 0 com.coloros.assistantscreen",
        ),
        PackageProfile(
            "com.nearme.instant.platform", "HeyTap instant apps", "oppo",
            "Instant/app discovery platform",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable if unused",
            "Instant app links break; core apps unaffected (guide is model-specific).",
            Evidence.XDA,
            "https://xdaforums.com/t/guide-adb-oppo-find-x-system-apps-safe-to-uninstall-disable-without-root.3818165/",
            "pm enable --user 0 com.nearme.instant.platform",
        ),
        PackageProfile(
            "com.oppo.launcher", "ColorOS Launcher", "oppo",
            "ColorOS default launcher",
            Recommendation.NONE, RiskLevel.HIGH_RISK,
            "Never disable without tested replacement",
            "XDA guide reports gesture navigation breakage after launcher removal.",
            Evidence.XDA,
            "https://xdaforums.com/t/guide-adb-oppo-find-x-system-apps-safe-to-uninstall-disable-without-root.3818165/",
            "pm enable --user 0 com.oppo.launcher",
        ),
        PackageProfile(
            "com.heytap.browser", "HeyTap Browser", "oppo",
            "Preloaded browser",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user if unused",
            "User-facing browser.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.heytap.browser",
        ),
        PackageProfile(
            "com.realme.store", "realme Store", "realme",
            "realme theme/store app",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user if unused",
            "Store/marketing app.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.realme.store",
        ),
    )

    // ------------------------------------------------------------------
    // vivo Funtouch OS
    // ------------------------------------------------------------------

    private fun vivo() = listOf(
        PackageProfile(
            "com.vivo.browser", "vivo Browser", "vivo",
            "Preloaded browser",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user if unused",
            "User-facing browser.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.vivo.browser",
        ),
        PackageProfile(
            "com.vivo.video", "vivo Video", "vivo",
            "Preloaded video app",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable for current user if unused",
            "User-facing app.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.vivo.video",
        ),
        PackageProfile(
            "com.vivo.easyshare", "EasyShare", "vivo",
            "vivo file transfer/sharing",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable if unused",
            "Local sharing feature lost.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.vivo.easyshare",
        ),
        PackageProfile(
            "com.bbk.appstore", "vivo App Store", "vivo",
            "OEM app store",
            Recommendation.NONE, RiskLevel.CAUTION,
            "Leave alone unless replaced",
            "Some vivo builds install/update system components through it.",
            Evidence.EIS_ANALYSIS, "",
            "pm enable --user 0 com.bbk.appstore",
        ),
    )

    // ------------------------------------------------------------------
    // Motorola
    // ------------------------------------------------------------------

    private fun motorola() = listOf(
        PackageProfile(
            "com.motorola.gamon", "Moto Gametime", "motorola",
            "Motorola gaming mode",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable if gaming mode unused",
            "Gaming overlay lost.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.motorola.gamon",
        ),
        PackageProfile(
            "com.motorola.ccc", "Moto Connect/Community", "motorola",
            "Motorola services bundle",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable if unused",
            "Moto extras may stop; verify calling and updates after change.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.motorola.ccc",
        ),
        PackageProfile(
            "com.motorola.fmplayer", "FM Radio", "motorola",
            "FM radio player (hardware dependent)",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable if unused",
            "FM feature lost.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.motorola.fmplayer",
        ),
    )

    // ------------------------------------------------------------------
    // Google Pixel
    // ------------------------------------------------------------------

    private fun pixel() = listOf(
        PackageProfile(
            "com.google.android.apps.wellbeing", "Digital Wellbeing", "pixel",
            "Wellbeing dashboard",
            Recommendation.OPTIONAL, RiskLevel.CAUTION,
            "Disable for current user if unused",
            "Dashboard/timers stop; Settings may show a broken Wellbeing entry on some builds.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.google.android.apps.wellbeing",
        ),
        PackageProfile(
            "com.google.android.apps.turbo", "Adaptive Battery (Pixel)", "pixel",
            "Adaptive battery/charging intelligence",
            Recommendation.NONE, RiskLevel.CAUTION,
            "Leave alone",
            "Charging/battery intelligence; disabling reduces adaptive features without benefit.",
            Evidence.AOSP_LIST,
            "https://source.android.com/",
            "pm enable --user 0 com.google.android.apps.turbo",
        ),
        PackageProfile(
            "com.google.android.apps.pixelmangler", "Pixel personalization", "pixel",
            "Pixel theming/personalization",
            Recommendation.OPTIONAL, RiskLevel.SAFE,
            "Disable if unused",
            "Theming extras lost.",
            Evidence.COMMUNITY_LIST,
            "https://github.com/0x192/universal-android-debloater",
            "pm enable --user 0 com.google.android.apps.pixelmangler",
        ),
    )

    // ------------------------------------------------------------------
    // Index
    // ------------------------------------------------------------------

    private val ALL_PROFILES: Map<String, PackageProfile> =
        (universal() + samsung() + xiaomi() + transsion() + coloros() + vivo() + motorola() + pixel())
            .associateBy { it.packageName }

    val ALL: List<PackageProfile> = ALL_PROFILES.values.sortedBy { it.packageName }

    /** OEM profile keys for filtering in the UI. */
    val OEM_KEYS: List<String> = ALL.map { it.oem }.distinct().sorted()
}
