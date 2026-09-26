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
        versionCode = 29
        versionName = "0.4.10"
    }

    signingConfigs {
        create("ci") {
            storeFile = file("ci-debug.keystore")
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
