# R8 keep rules for the FlashForge Farm app.
#
# The app is kept wholesale: it is self-developed logic with wide reflection
# (Gson over its own models, WebView JS bridges, native bridge callbacks), and
# is small next to the native engine payload. Broad keeps eliminate the
# per-feature keep churn while still letting R8 strip unused THIRD-PARTY code,
# inline, and shrink resources.

-keep class com.flashforge.farm.** { *; }

# Gson reflects over its model classes by name.
-keep class com.google.gson.** { *; }

# JNA (iroh FFI / libjnidispatch) resolves native methods by name.
-keep class net.java.dev.jna.** { *; }
-keep class com.sun.jna.** { *; }

# Iroh P2P (Kotlin FFI over JNA).
-keep class computer.iroh.** { *; }
-keep class io.iroh.** { *; }

# TrueTime (NTP) reads class annotations at runtime.
-keep class com.github.instacart.** { *; }

# LoopJ async HTTP client.
-keep class com.loopj.android.http.** { *; }

# Colorpicker + Glide (loader) view libs.
-keep class com.github.mrudultora.** { *; }
-keep class com.bumptech.glide.** { *; }

# Missing-ref noise from JNA/iroh/third-party bytecode that references
# optional classes; R8 must not fail the build on those.
-dontwarn net.java.dev.jna.**
-dontwarn com.sun.jna.**
-dontwarn computer.iroh.**
-dontwarn io.iroh.**
-dontwarn com.github.instacart.**
-dontwarn com.loopj.android.http.**
-dontwarn com.github.mrudultora.**
-dontwarn com.bumptech.glide.**