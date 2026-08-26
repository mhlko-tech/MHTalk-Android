plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun configValue(name: String, fallback: String = ""): String =
    (providers.gradleProperty(name).orNull ?: System.getenv(name) ?: fallback)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "com.mhlko.talk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mhlko.talk"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.4.0"

        buildConfigField("String", "LIVEKIT_URL", "\"wss://mhtalkremake-utuei6i7.livekit.cloud\"")
        buildConfigField("String", "TOKEN_ENDPOINT", "\"https://mhtalk-token-service.mhlkotalk.workers.dev/livekit/token\"")
        buildConfigField("String", "SUPABASE_URL", "\"${configValue("MHTALK_SUPABASE_URL", "https://fcadjrqrrzcvbyqrgnnm.supabase.co")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${configValue("MHTALK_SUPABASE_PUBLISHABLE_KEY", "sb_publishable_3Azp3R7eFE8YI81Eg_Bekw_D353_Efc")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${configValue("MHTALK_FIREBASE_PROJECT_ID", "mhtalk-d5f01")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${configValue("MHTALK_FIREBASE_APP_ID", "1:1013525860234:android:f77f491d0a26728589c273")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${configValue("MHTALK_FIREBASE_API_KEY", "AIzaSyBn6zhT9gD3eAN3cG8OC5Oz9e3wqY-Kvo0")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${configValue("MHTALK_FIREBASE_SENDER_ID", "1013525860234")}\"")
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("MHTALK_ANDROID_KEYSTORE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("MHTALK_ANDROID_STORE_PASSWORD")
                keyAlias = System.getenv("MHTALK_ANDROID_KEY_ALIAS") ?: "mhtalk"
                keyPassword = System.getenv("MHTALK_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    // Compose 1.11 is the newest stable line that supports compileSdk 36.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-gif:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.livekit:livekit-android:2.28.0")
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-messaging")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
