plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.translator.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.translator.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "3.1.0"

        ndk {
            // 只编译 arm64-v8a（支持所有现代手机）
            // 如果需要在旧设备上运行，可以加 armeabi-v7a
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // NDK / CMake 配置
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ML Kit GenAI Prompt API — 调用本地 Gemma 4 做翻译
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
}
