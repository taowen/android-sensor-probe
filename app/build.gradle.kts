plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.sensorprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.sensorprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-community-diagnostics"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    ndkVersion = "29.0.14206865"
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
}
