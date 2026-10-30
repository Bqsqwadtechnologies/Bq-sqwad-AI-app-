plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val releaseSigningReady = listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    .all { !System.getenv(it).isNullOrBlank() }

android {
    namespace = "com.bqsqwad.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bqsqwad.ai"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev\"")
        minSdk = 24
        targetSdk = 36
        versionCode = 14
        versionName = "1.0.14"
        val buildSha = System.getenv("GITHUB_SHA")?.take(7) ?: "local"
        buildConfigField("String", "BUILD_SHA", "\"$buildSha\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
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
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    // Remote member avatars in the native Teams workspace.
    implementation("io.coil-kt:coil-compose:2.7.0")

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
    testImplementation("junit:junit:4.13.2")
    // Provide a real JSON implementation to local JVM tests instead of Android's unmocked stub.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}


gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any {
        it.project == project &&
            (it.name.startsWith("assemble") || it.name.startsWith("bundle") || it.name.startsWith("package")) &&
            it.name.endsWith("Release")
    }
    if (wantsRelease) {
        check(releaseSigningReady) {
            "Release signing is not configured. Set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD before building a release artifact."
        }
    }
}
