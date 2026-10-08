plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kimt9.pausegate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kimt9.pausegate"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 개인용 APK: 디버그 키로 서명해 바로 설치 가능하게 한다.
            signingConfig = signingConfigs.getByName("debug")
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
}
