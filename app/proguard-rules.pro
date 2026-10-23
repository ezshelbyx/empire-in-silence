# Shizuku provider and UserService must be kept (reflection-based binder entry points)
-keep class rikka.shizuku.ShizukuProvider { *; }
-keep class com.eis.silentoptimizer.privilege.** { *; }
-keep class com.eis.silentoptimizer.shell.** { *; }

# AIDL / binder stubs
-keep class * implements android.os.IInterface { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.eis.silentoptimizer.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.eis.silentoptimizer.**$$serializer { *; }
-dontwarn org.jetbrains.annotations.**
