import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "com.jjmluz.photostv.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jjmluz.photostv.phone"
        minSdk = 29
        targetSdk = 37
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
    }

    signingConfigs {
        // The release key, from local.properties (see README, Build). Without it, release builds are
        // signed with the debug key, which only installs over builds from the same machine.
        val storeFile = localProperties.getProperty("release.storeFile")
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = localProperties.getProperty("release.storePassword")
                keyAlias = localProperties.getProperty("release.keyAlias")
                keyPassword = localProperties.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 breaks ML Kit's start-up (the scanner crashes with an NPE in its logger), and the
            // app is small enough not to need it.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    testOptions {
        // Unit tests run on the JVM: android.util.Log and friends do nothing instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // English, Portuguese, Spanish, French and German, so Android lists them in the app's
        // language settings (res/resources.properties names the default, English).
        generateLocaleConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    // Play Services' scanner: no camera permission, the system draws the viewfinder.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // The scanner brings an old Fragment, which breaks the permission request.
    implementation("androidx.fragment:fragment:1.9.1")

    testImplementation("junit:junit:4.13.2")
    // The platform's org.json is only a stub on the JVM.
    testImplementation("org.json:json:20260814")
}
