plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 固定签名：CI 会把 Secret 里的 keystore 写到仓库根目录的 debug.keystore。
// 显式指定签名配置，避免 AGP 在 $HOME/.android 下自己生成随机 debug 钥匙。
val fixedKeystore = rootProject.file(System.getenv("DEBUG_KEYSTORE_PATH") ?: "debug.keystore")

android {
    namespace = "com.luffy.adbwatchdog"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.luffy.adbwatchdog"
        minSdk = 30
        targetSdk = 34
        versionCode = 9
        versionName = "0.2.7"
    }

    signingConfigs {
        create("fixedDebug") {
            storeFile = fixedKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            if (fixedKeystore.exists()) {
                signingConfig = signingConfigs.getByName("fixedDebug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
