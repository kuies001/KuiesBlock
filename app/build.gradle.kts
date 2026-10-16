plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is supplied entirely through environment variables so that no
// keystore or password ever lives in the repository. Debug builds never need it.
val releaseSigningValues = mapOf(
    "storeFile" to providers.environmentVariable("KUIESBLOCK_KEYSTORE_FILE").orNull,
    "keyAlias" to providers.environmentVariable("KUIESBLOCK_KEY_ALIAS").orNull,
    "keyPassword" to providers.environmentVariable("KUIESBLOCK_KEY_PASSWORD").orNull,
    "storePassword" to providers.environmentVariable("KUIESBLOCK_STORE_PASSWORD").orNull
)
val hasAnyReleaseSigningValue = releaseSigningValues.values.any { !it.isNullOrBlank() }
val hasCompleteReleaseSigning = releaseSigningValues.values.all { !it.isNullOrBlank() }

if (hasAnyReleaseSigningValue && !hasCompleteReleaseSigning) {
    throw GradleException("Release signing requires all KUIESBLOCK_* signing environment variables.")
}

android {
    namespace = "tw.kuies.block"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "tw.kuies.block"
        minSdk = 28
        targetSdk = 37
        versionCode = 6
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningValues["storeFile"]))
                keyAlias = requireNotNull(releaseSigningValues["keyAlias"])
                keyPassword = requireNotNull(releaseSigningValues["keyPassword"])
                storePassword = requireNotNull(releaseSigningValues["storePassword"])
            }
        }
    }

    buildTypes {
        release {
            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // Robolectric needs the merged resources, and the WorkManager test harness needs a real Context.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.okhttp.tls)
    // The official WorkManager test harness, so the scheduler and the worker can be verified without a
    // device: no emulator on this machine has ever booted, so instrumented tests have no target.
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
