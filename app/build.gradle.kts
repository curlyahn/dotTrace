plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dot.trace"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dot.trace"
        minSdk = 26
        targetSdk = 35
        // GitHub Actions 실행 번호를 빌드 번호로 쓴다 (업데이트 설치가 가능하도록 항상 증가)
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1"
    }

    signingConfigs {
        // GitHub Actions: 저장소 Secret의 키를 DOT_KEYSTORE 경로로 풀어 두면 그 키로 서명한다.
        getByName("debug") {
            System.getenv("DOT_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { path ->
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    // 매월 1일·매년 1일 리포트 알림
    implementation(libs.androidx.work.runtime.ktx)

    // 지도: MapLibre (API 키 없이 OpenFreeMap 지도 사용)
    implementation(libs.maplibre.android)
    implementation(libs.play.services.location)
    // Play 서비스가 끌어오는 오래된 Fragment를 최신으로 (권한 요청 API 요구사항)
    implementation(libs.androidx.fragment)
}
