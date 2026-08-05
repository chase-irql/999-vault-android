# ADR 0006: Pinned Android 17 toolchain

Status: accepted

Use AGP 9.1.1, Gradle 9.3.1, Kotlin/Compose compiler plugin 2.3.21, Compose BOM 2026.06.00, compile/target SDK 37, min SDK 26, and JDK 17. AGP 9 built-in Kotlin is enabled in Android modules; the standalone model module applies Kotlin JVM 2.3.21. Official Android documentation confirms AGP 9.1 requires Gradle 9.3.1 and API 37 setup. JetBrains lists Gradle 9.3.0/AGP 9.0.0 as the fully tested maxima for KGP 2.3.21 but explicitly permits later versions with possible warnings or unavailable features; Google’s current Compose setup uses compiler plugin 2.3.21 with AGP 9 and API 37. The requested pins therefore remain unchanged pending a real clean build.

Stable Baseline Profile plugin 1.4.1 does not understand the AGP 9 new `TestExtension` implementation. AndroidX 1.5 fixes that integration but is alpha-only on the inspection date, so preview artifacts remain forbidden and `android.newDsl=false` is set as the stable plugin's documented AGP 9 compatibility path. Built-in Kotlin remains enabled; the removed `org.jetbrains.kotlin.android` plugin is not applied.
