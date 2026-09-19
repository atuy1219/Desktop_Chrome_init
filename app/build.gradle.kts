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
