import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.blacklab.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blacklab.app"
        minSdk = 35
        targetSdk = 36
        versionCode = 12
        versionName = "5.0.0"
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file("keystore/" + keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Uses the private release key when keystore/keystore.properties exists (never committed);
            // otherwise falls back to the debug key so anyone can build from source.
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    // Room schema JSONs feed the migration tests on a device
    sourceSets { getByName("androidTest").assets.srcDir("$projectDir/schemas") }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "androidx.camera.camera2.interop.ExperimentalCamera2Interop",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.1")
    implementation("androidx.biometric:biometric:1.1.0")

    val room = "2.7.2"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // 1.6+: Extensions (Night/Portrait/HDR) keep working after CameraX ≤1.5 support ends on 2026-11-01
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-extensions:$camerax")

    val media3 = "1.9.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Background music playback (notification / lock-screen controls) and the video & audio editors
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    // PDF editing: merge, split, rotate, reorder, text extraction, stamping, forms, passwords
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    constraints {
        // pdfbox-android pulls Bouncy Castle 1.72, which has published CVEs (CVE-2024-29857,
        // CVE-2024-30171, CVE-2024-30172, CVE-2024-34447) reachable through crafted PDFs/certificates
        val bc = "1.80"
        listOf("bcprov", "bcpkix", "bcutil").forEach { m ->
            implementation("org.bouncycastle:$m-jdk15to18:$bc") { because("security fixes over 1.72") }
        }
    }

    // Home-screen widgets (Today, Checklist, Quick capture)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1") // widgets in your palette colors

    // QR codes for Brainstorm Live Share links
    implementation("com.google.zxing:core:3.5.3")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("org.jsoup:jsoup:1.21.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")

    // Assistant: on-device Gemini Nano (AICore), Google sign-in for Gmail/Drive, background sync
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
    implementation("com.google.android.gms:play-services-auth:21.5.1")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    // Android Auto: templated car screens (assistant, agenda, tasks, Santa Fe garage, knowledge talk) and
    // car hardware info (model, fuel level, odometer) when the vehicle shares it
    val carApp = "1.7.0"
    implementation("androidx.car.app:app:$carApp")
    implementation("androidx.car.app:app-projected:$carApp")
    // Installs the bundled baseline profile so BlackLab's code is precompiled: faster start, smoother first scrolls
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // On-device tests (run on the emulator locally and in CI)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.room:room-testing:$room")
    // TestCarContext for the Android Auto template checks; Robolectric (pulled in transitively) would
    // replace GrantPermissionRule's on-device permission granter, so it's excluded
    androidTestImplementation("androidx.car.app:app-testing:$carApp") { exclude(group = "org.robolectric") }
}
