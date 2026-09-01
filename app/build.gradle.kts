plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.readest.multitts"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readest.multitts"
        minSdk = 24
        targetSdk = 34
        versionCode = 18
        versionName = "1.18.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two distributions from one codebase:
    //   standard - lean, narrates through MultiTTS or the phone's own engine
    //   bundled  - carries its own offline voices, for handing to someone who
    //              has set nothing up
    // They are separate release tracks, so neither ever offers the other's
    // build as an update.
    flavorDimensions += "voices"

    productFlavors {
        create("standard") {
            dimension = "voices"
            buildConfigField("String", "RELEASE_TRACK", "\"standard\"")
        }
        create("bundled") {
            dimension = "voices"
            buildConfigField("String", "RELEASE_TRACK", "\"bundled\"")
            ndk {
                // 64-bit only. Adding armeabi-v7a costs another 16MB of native
                // code for a share of devices that is now vanishingly small.
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.webkit:webkit:1.11.0")

    // Media & Audio Playback for Energy-Saving Local Audio Caching
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-session:1.4.0")
    implementation("androidx.media3:media3-ui:1.4.0")

    // Coroutines & Networking for downloads
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Real PDF text extraction (handles compressed streams and font encodings)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // org.json is stubbed out in unit tests; the real one makes them runnable.
    testImplementation("org.json:json:20240303")
    // Offline neural TTS (Apache-2.0). Local AAR: k2-fsa publishes no Maven artifact.
    "bundledImplementation"(files("libs/sherpa-onnx.aar"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
