plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jjmluz.photostv.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jjmluz.photostv.phone"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 breaks ML Kit's start-up (the scanner crashes with an NPE in its logger), and the
            // app is small enough not to need it.
            isMinifyEnabled = false
            // Sideloaded personal build, like the TV app.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Play Services' scanner: no camera permission, the system draws the viewfinder.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // The scanner brings an old Fragment, which breaks the permission request.
    implementation("androidx.fragment:fragment:1.8.6")
}
