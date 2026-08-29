plugins {
    alias(libs.plugins.android.library)
    // Kotlin is provided by AGP 9's built-in Kotlin support, exactly as in :app.
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tv.own.owntv.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        // Seven core files use Compose *runtime* only (Immutable, remember, LaunchedEffect,
        // CompositionLocalProvider). No ui, no foundation, no androidx.tv — see the dependency
        // block below, and the "core stays UI-framework-neutral" invariant.
        compose = true
        buildConfig = true
    }

    testOptions {
        // Mirrors :app — code under test touches android.util.Log / SystemClock and must get
        // defaults rather than "not mocked" crashes.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// OwnTVDatabaseMigrationTest opens each shipped schema from assets to replay every migration, so
// `core/schemas/` has to be packaged with the instrumentation APK. Attached through the Variant API
// rather than `sourceSets["androidTest"]`, which throws a ClassCastException in an AGP 9 library.
androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory("$projectDir/schemas")
    }
}

// The @Database and its exported schemas (`core/schemas/`) both live here.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Database (Room, via KSP) + Paging. No paging-compose — that is UI.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime)

    // Preferences + durable background sync
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    // Android TV launcher integration (Watch Next / preview channels). TV-only in practice, but
    // core's LauncherIntegrationRepository is the facade the sync worker calls, so the publisher it
    // delegates to has to live here too. A phone app simply never calls those methods.
    implementation(libs.androidx.tvprovider)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.zxing.core) // QR generation for the Remote (companion) add-source flow
    implementation(libs.juniversalchardet) // local subtitle charset detection

    // Dependency injection
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // Compose RUNTIME ONLY, via the same BOM :app uses. Deliberately not ui/foundation/material,
    // not androidx.tv.*, not navigation, not media3, not coil.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)

    testImplementation(libs.junit)
    // android.jar's org.json is a stub and isReturnDefaultValues silences it; backup/restore is
    // all JSON, so the tests need the real implementation. Same reason as :app.
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
}
