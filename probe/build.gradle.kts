plugins {
    id("com.android.application")
}

android {
    namespace = "com.atuy.chromedesktopprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.atuy.chromedesktopprobe"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("ci") {
            storeFile = file("../app/ci-debug.keystore")
            storePassword = "desktopchromeinit"
            keyAlias = "desktopchromeinit"
            keyPassword = "desktopchromeinit"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ci")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
