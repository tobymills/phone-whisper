plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kafkasl.phonewhisper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kafkasl.phonewhisper"
        minSdk = 30
        targetSdk = 34
        versionCode = System.getenv("PHONE_WHISPER_VERSION_CODE")?.toIntOrNull() ?: 3
        versionName = "0.4.0"

        ndk { abiFilters += "arm64-v8a" }
    }

    // Configure the installable APK only when CI has an explicitly supplied
    // persistent key. Local debug builds remain independent of signing secrets.
    val stableKeystore = System.getenv("PHONE_WHISPER_KEYSTORE_FILE")
    if (!stableKeystore.isNullOrBlank()) {
        signingConfigs {
            create("stable") {
                storeFile = file(stableKeystore)
                storePassword = System.getenv("PHONE_WHISPER_STORE_PASSWORD")
                    ?: error("Missing PHONE_WHISPER_STORE_PASSWORD")
                keyAlias = System.getenv("PHONE_WHISPER_KEY_ALIAS")
                    ?: error("Missing PHONE_WHISPER_KEY_ALIAS")
                keyPassword = System.getenv("PHONE_WHISPER_KEY_PASSWORD")
                    ?: error("Missing PHONE_WHISPER_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
