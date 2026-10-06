plugins {
    id("com.android.application")
}

android {
    namespace = "it.toknative.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.toknative.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-beta"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.tiktok.open.sdk:tiktok-open-sdk-core:latest.release")
    implementation("com.tiktok.open.sdk:tiktok-open-sdk-auth:latest.release")
}
