plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.videograbber.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.videograbber.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // youtubedl-android ships native libs for these ABIs; a universal APK
        // that includes them all runs on any Android phone.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
    }

    packaging {
        // youtubedl-android extracts its bundled python/ffmpeg from the APK at
        // runtime, so the native libs must NOT be compressed/merged away.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- yt-dlp + ffmpeg engine for Android (the core downloader) ---
    // Maven Central fork (io.github.junkfood02) — the maintained distribution
    // the Seal app uses. 0.18.1 bundles Python 3.12.11 + QuickJS + a recent
    // yt-dlp, and supports updateYoutubeDL() at runtime. (The old JitPack
    // coords only had 0.15.0 with Python 3.8, which CANNOT run current yt-dlp
    // — that was why extraction failed on every site.)
    val ytdlp = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlp")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlp")

    // --- Jetpack Compose UI ---
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // --- thumbnails ---
    implementation("io.coil-kt:coil-compose:2.7.0")

    // --- coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
