plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.anylisten.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anylisten.mobile"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.2"
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (!ksFile.isNullOrBlank()) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ks = signingConfigs.findByName("release")
            // 未配置 Secrets 时退回 debug 签名，保证 release 包可直接安装测试
            signingConfig = if (ks?.storeFile?.exists() == true) ks else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.media:media:1.7.0")
}
