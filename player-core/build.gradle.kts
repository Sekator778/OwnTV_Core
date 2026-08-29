plugins {
    alias(libs.plugins.android.library)
    // Kotlin is provided by AGP 9's built-in Kotlin support, exactly as in :app and :core. The
    // library plugin must NOT be applied with a version from here — see :core's Phase 1 note.
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "tv.own.owntv.playercore"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        // Compose *runtime* only, same rule as :core — the engine holds Compose state that the HUD
        // observes, but it renders nothing. No ui, no foundation, no androidx.tv.
        compose = true
    }

    testOptions {
        // Mirrors :app and :core — code under test touches android.util.Log / SystemClock and must
        // get defaults rather than "not mocked" crashes.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The engine reads settings, playback preferences and DB entities, and reports into core's
    // hooks. The arrow points this way only: :core never sees :player-core.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Playback — libmpv (FFmpeg) plus the Media3/ExoPlayer path used for VOD, image subtitles and
    // the Live/hero preview panes. Same set :app carried before the split.
    implementation(libs.libmpv)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.okhttp)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // Compose RUNTIME ONLY, via the same BOM :app uses. Deliberately not ui/foundation/material,
    // not androidx.tv.*, not navigation, not coil.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)

    testImplementation(libs.junit)
}
