import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing is configured via android/keystore.properties (not committed):
//   storeFile=/absolute/path/to/release.keystore
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.bvb.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bvb.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 69
        versionName = "0.1.68"

        // Backend base URL; override per build type or with -PbvbBaseUrl=...
        val baseUrl = (project.findProperty("bvbBaseUrl") as String?) ?: "http://10.0.2.2:8080"
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")

        // Cloudflare Turnstile site key; empty string = widget skipped
        // (matches a backend running with Turnstile.Enabled=false).
        val turnstileSiteKey = (project.findProperty("bvbTurnstileSiteKey") as String?) ?: ""
        buildConfigField("String", "TURNSTILE_SITE_KEY", "\"$turnstileSiteKey\"")
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Emulator loopback to the host machine by default.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.pgpainless.core)
    implementation(libs.bouncycastle.provider)
    implementation(libs.bouncycastle.pg)
    implementation(libs.bouncycastle.util)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
