plugins {
    id("com.android.application")
}

android {
    namespace = "com.atuy.desktopchromeinit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.atuy.desktopchromeinit"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            // This repository publishes CI test builds rather than Play Store
            // releases. Sign the release variant with the standard Android
            // debug key so the generated APK is directly installable.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
