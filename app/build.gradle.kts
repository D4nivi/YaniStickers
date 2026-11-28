plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.danivi.yanistickers"
    compileSdk {
        version = release(37)
    }

    val ciVersionName = (providers.gradleProperty("appVersionName").orNull
        ?: providers.environmentVariable("APP_VERSION_NAME").orNull)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val ciVersionCode = (providers.gradleProperty("appVersionCode").orNull
        ?: providers.environmentVariable("APP_VERSION_CODE").orNull)
        ?.trim()
        ?.toIntOrNull()

    val keystorePath = providers.environmentVariable("SIGNING_KEYSTORE_PATH").orNull?.takeIf { it.isNotBlank() }
    val keystorePassword = providers.environmentVariable("SIGNING_KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
    val releaseKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
    val releaseKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
    val hasReleaseSigning = keystorePath != null &&
        keystorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

    defaultConfig {
        applicationId = "io.github.danivi.yanistickers"
        minSdk = 30
        targetSdk = 37
        versionCode = ciVersionCode ?: 10000
        versionName = ciVersionName ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}