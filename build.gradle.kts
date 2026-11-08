plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

buildscript {
    dependencies {
        // AGP's built-in Kotlin defaults to 2.2; the Compose BOM needs the 2.4 stdlib.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

// Release builds take their version from the git tag the release workflow builds (`v1.2.3`,
// passed as -PappVersion=1.2.3); local builds are 0.1.0. versionCode is 1_02_03 for 1.2.3.
val appVersion = providers.gradleProperty("appVersion").orNull ?: "0.1.0"
extra["appVersionName"] = appVersion
extra["appVersionCode"] = appVersion.substringBefore('-').split('.').map(String::toInt)
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
    .coerceAtLeast(1)
