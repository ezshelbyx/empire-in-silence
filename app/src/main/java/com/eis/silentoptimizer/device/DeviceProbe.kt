package com.eis.silentoptimizer.device

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.WindowManager

/**
 * Device intelligence (spec 3, 11). Reads only public, unprivileged APIs.
 * Every field is nullable or marked "unavailable" when the platform or OEM
 * refuses to expose it - we never fabricate values.
 */
data class DeviceProfile(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val hardware: String,
    val androidVersion: String,
    val sdkInt: Int,
    val securityPatch: String?,
    val buildId: String,
    val supportedAbis: List<String>,
    val totalRamBytes: Long,
    val totalStorageBytes: Long,
    val availableStorageBytes: Long,
    val cpuCores: Int,
    val isEmulator: Boolean,
    val oemSkin: OemSkin,
)

/** Coarse OEM skin classification used to pick a debloat profile. */
enum class OemSkin {
    SAMSUNG_ONE_UI,
    XIAOMI_HYPEROS_OR_MIUI,
    TECNO_HIOS,
    INFINEIX_XOS,
    OPPO_COLOROS,
    REALME_UI,
    VIVO_FUNTOUCH,
    MOTOROLA,
    GOOGLE_PIXEL,
    GENERIC_AOSP,
}

data class BatterySnapshot(
    val levelPercent: Int,
    val isCharging: Boolean,
    val temperatureC: Double?,
    val voltageMv: Int?,
    val healthLabel: String,
    val chargeCounterUah: Long?,
    val currentNowUa: Long?,
    val technology: String?,
)

data class ThermalSnapshot(
    /** PowerManager thermal status 0..6 (NONE..SHUTDOWN). */
    val thermalStatus: Int,
    val thermalStatusLabel: String,
    /** Battery temperature from the battery intent, if reported. */
    val batteryTemperatureC: Double?,
    /** Per-zone readings from /sys/class/thermal when readable. */
    val zones: List<ThermalZone>,
)

data class ThermalZone(val name: String, val temperatureC: Double?)

object DeviceProbe {

    @SuppressLint("WrongConstant", "NewApi")
    fun profile(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val dataDir = Environment.getDataDirectory()
        val stat = StatFs(dataDir.path)
        val totalStorage = stat.totalBytes
        val availStorage = stat.availableBytes

        val skin = detectSkin()

        return DeviceProfile(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            product = Build.PRODUCT,
            hardware = Build.HARDWARE,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            buildId = Build.DISPLAY,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = memInfo.totalMem,
            totalStorageBytes = totalStorage,
            availableStorageBytes = availStorage,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            isEmulator = detectEmulator(),
            oemSkin = skin,
        )
    }

    fun detectSkin(): OemSkin {
        val mfr = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return when {
            mfr.contains("samsung") -> OemSkin.SAMSUNG_ONE_UI
            mfr.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ||
                mfr.contains("redmi") -> OemSkin.XIAOMI_HYPEROS_OR_MIUI
            mfr.contains("tecno") -> OemSkin.TECNO_HIOS
            mfr.contains("infinix") -> OemSkin.INFINEIX_XOS
            mfr.contains("oppo") -> OemSkin.OPPO_COLOROS
            mfr.contains("realme") -> OemSkin.REALME_UI
            mfr.contains("vivo") || brand.contains("vivo") -> OemSkin.VIVO_FUNTOUCH
            mfr.contains("motorola") || brand.contains("motorola") -> OemSkin.MOTOROLA
            mfr.contains("google") -> OemSkin.GOOGLE_PIXEL
            else -> OemSkin.GENERIC_AOSP
        }
    }

    private fun detectEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        return fp.contains("generic") || fp.contains("emulator") || fp.contains("sdk_gphone") ||
            model.contains("emulator") || model.contains("sdk_gphone") ||
            product.contains("sdk") || hardware.contains("goldfish") ||
            hardware.contains("ranchu") || Build.BOARD.contains("goldfish")
    }

    fun battery(context: Context): BatterySnapshot {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val level = intent?.let {
            val raw = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (raw >= 0 && scale > 0) raw * 100 / scale else -1
        } ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1

        return BatterySnapshot(
            levelPercent = if (level >= 0) level else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            temperatureC = if (tempTenths > 0) tempTenths / 10.0 else null,
            voltageMv = if (voltage > 0) voltage else null,
            healthLabel = healthLabel(health),
            chargeCounterUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                .takeIf { it > 0 },
            currentNowUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                .takeIf { it != Long.MIN_VALUE && it != 0L },
            technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
        )
    }

    private fun healthLabel(code: Int): String = when (code) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }

    @SuppressLint("WrongConstant")
    fun thermal(context: Context): ThermalSnapshot {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val status = try { pm.currentThermalStatus } catch (t: Throwable) { -1 }
        val batteryTemp = battery(context).temperatureC
        return ThermalSnapshot(
            thermalStatus = status,
            thermalStatusLabel = thermalLabel(status),
            batteryTemperatureC = batteryTemp,
            zones = readThermalZones(),
        )
    }

    fun thermalLabel(status: Int): String = when (status) {
        0 -> "None"
        1 -> "Light"
        2 -> "Moderate"
        3 -> "Severe"
        4 -> "Critical"
        5 -> "Emergency"
        6 -> "Shutdown"
        else -> "Unavailable"
    }

    /**
     * Best-effort read of /sys/class/thermal. On most production builds this
     * directory is mode 0750 root:root, so this returns an empty list for
     * unprivileged apps - which we surface as "unavailable", never as zero.
     */
    fun readThermalZones(limit: Int = 24): List<ThermalZone> {
        val zones = mutableListOf<ThermalZone>()
        try {
            val base = java.io.File("/sys/class/thermal")
            val entries = base.listFiles()?.filter { it.name.startsWith("thermal_zone") }
                ?.sortedBy { it.name } ?: return emptyList()
            for (zone in entries.take(limit)) {
                val name = java.io.File(zone, "type").readTextOrNull()?.trim() ?: zone.name
                val temp = java.io.File(zone, "temp").readTextOrNull()?.trim()?.toLongOrNull()
                if (temp != null) {
                    // Millidegrees on most kernels, degrees on some vendors.
                    val celsius = if (temp > 1000) temp / 1000.0 else temp.toDouble()
                    if (celsius in -40.0..150.0) zones.add(ThermalZone(name, celsius))
                }
            }
        } catch (_: Throwable) {
            // Unreadable: return what we have (usually nothing).
        }
        return zones
    }

    private fun java.io.File.readLineOrNull(): String? = try {
        if (canRead()) readTextOrNull()?.lineSequence()?.firstOrNull() else null
    } catch (_: Throwable) { null }

    private fun java.io.File.readTextOrNull(): String? = try {
        if (canRead()) readText() else null
    } catch (_: Throwable) { null }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return "%.1f %s".format(value, units[unit])
    }
}
