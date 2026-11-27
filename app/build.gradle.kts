plugins {
    id("com.android.application")
}

android {
    namespace = "com.atuy.chromemobilecompat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.atuy.chromemobilecompat"
        minSdk = 29
        targetSdk = 35
        versionCode = 42
        versionName = "0.5.7-leading-favicon"
        ndk { abiFilters += "arm64-v8a" }
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

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
