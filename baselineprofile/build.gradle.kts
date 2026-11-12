// Generates BlackLab's Baseline Profile (precompiled startup + Home code):
//   ./gradlew :app:generateReleaseBaselineProfile     (needs the emulator running; writes app/src/release/generated/baselineProfiles)
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.blacklab.baselineprofile"
    compileSdk = 37
    defaultConfig {
        minSdk = 35
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.uiautomator:uiautomator:2.4.1")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
