plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val releaseKeystore: String? = System.getenv("CAMALERT_KEYSTORE")

android {
    namespace = "com.gom.camalert"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gom.camalert"
        minSdk = 29
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        // 카카오 SDK 네이티브 라이브러리 중 폰용(arm)만 포함해 APK 크기를 줄임
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        // GitHub Secrets의 KAKAO_NATIVE_APP_KEY. 없으면 빈 문자열로 빌드되고 카메라 정보 없이 동작
        buildConfigField("String", "KAKAO_APP_KEY", "\"${System.getenv("KAKAO_NATIVE_APP_KEY") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("CAMALERT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CAMALERT_KEY_ALIAS")
                keyPassword = System.getenv("CAMALERT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 같은 키로 서명해야 폰에서 덮어쓰기 업데이트가 됨
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.kakaomobility.knsdk:knsdk:1.12.8-hotfix03")
    testImplementation("junit:junit:4.13.2")
}
