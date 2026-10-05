plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val releaseSigningReady = listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    .all { !System.getenv(it).isNullOrBlank() }

android {
    namespace = "com.bqsqwad.ai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bqsqwad.ai"
        buildConfigField("String", "API_BASE_URL", "\"https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev\"")
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.5"
        val buildSha = System.getenv("GITHUB_SHA")?.take(7) ?: "local"
        buildConfigField("String", "BUILD_SHA", "\"$buildSha\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            if (releaseSigningReady) {
                signingConfig = signingConfigs.create("releaseCi") {
                    storeFile = file(requireNotNull(System.getenv("KEYSTORE_PATH")))
                    storePassword = requireNotNull(System.getenv("KEYSTORE_PASSWORD"))
                    keyAlias = requireNotNull(System.getenv("KEY_ALIAS"))
                    keyPassword = requireNotNull(System.getenv("KEY_PASSWORD"))
                }
            }
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.10.1")

    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.android.gms:play-services-tasks:18.3.2")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.android.billingclient:billing:9.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.github.thibaultbee.streampack:streampack-core:3.2.0")
    implementation("io.github.thibaultbee.streampack:streampack-rtmp:3.2.0")
    implementation("io.github.thibaultbee.streampack:streampack-compose:3.2.0")
    implementation("androidx.biometric:biometric:1.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
