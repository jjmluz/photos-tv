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
