import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.voiceproto"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.voiceproto"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // Personal defaults (server address, user) come from voice.properties, which is not in the repository;
        // see voice.properties.example. Without it the fields start empty and are filled in the app's settings.
        val voice = Properties().apply {
            rootProject.file("voice.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
        }
        fun quoted(key: String) = "\"" + voice.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        buildConfigField("String", "DEFAULT_SERVER_HOST", quoted("serverHost"))
        buildConfigField("String", "DEFAULT_SERVER_USER", quoted("serverUser"))

        ndk {
            // Practically all current phones are arm64; x86_64 is for the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Always optimize the native code, even in debug builds: Whisper is unusably slow otherwise.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Prototype: sign release builds with the debug key so the APK can be installed directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
        // sherpa-onnx (in-app Piper voice) is only needed on the arm64 phone.
        jniLibs {
            excludes += listOf("lib/x86_64/libonnxruntime.so", "lib/x86_64/libsherpa-onnx-*.so")
        }
        resources {
            excludes += listOf("META-INF/versions/*/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // SSH client (maintained JSch fork) + BouncyCastle for Ed25519 keys on Android
    implementation("com.github.mwiede:jsch:2.28.7")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    // In-app Piper voice: sherpa-onnx runtime (v1.13.8 release AAR, sha256 633c2432...bd96) and bzip2/tar for the model.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
}
