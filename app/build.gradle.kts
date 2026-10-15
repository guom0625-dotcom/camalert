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
    testImplementation("junit:junit:4.13.2")
}
