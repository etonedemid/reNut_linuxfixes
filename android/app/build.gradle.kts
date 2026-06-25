plugins {
    id("com.android.application")
}

android {
    namespace = "com.rexglue.renut"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rexglue.renut"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootProject.projectDir}/renut-release.jks")
            storePassword = "renutrelease"
            keyAlias = "renut"
            keyPassword = "renutrelease"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Use the prebuilt librenut.so from the CMake build directory
    sourceSets {
        getByName("main") {
            // Assets are too large for APK (~6GB) -- push them to device storage
            // via: adb push assets/ /sdcard/Android/data/com.rexglue.renut/files/assets/
        }
        getByName("debug") {
            jniLibs.srcDirs("${rootProject.projectDir}/../build-android/jniLibs")
        }
        getByName("release") {
            jniLibs.srcDirs("${rootProject.projectDir}/../build-android/jniLibs-release")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
