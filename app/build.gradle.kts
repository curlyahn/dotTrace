import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Google Maps API 키: GitHub Actions에서는 MAPS_API_KEY Secret(환경 변수),
// PC에서는 local.properties의 MAPS_API_KEY=... 에서 읽는다. 저장소에는 절대 올리지 않는다.
val mapsApiKey: String = System.getenv("MAPS_API_KEY")?.takeIf { it.isNotBlank() }
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }.getProperty("MAPS_API_KEY")
    }
    ?: ""

android {
    namespace = "com.dot.trace"
    compileSdk = 35

    defaultConfig {
        // Google Cloud Console의 Maps API 키 제한(Android 앱)에 이 패키지 이름을 등록하세요.
        applicationId = "com.dot.trace"
        minSdk = 26
        targetSdk = 35
        // GitHub Actions 실행 번호를 빌드 번호로 쓴다 (업데이트 설치가 가능하도록 항상 증가)
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        // 지도 키 없이 빌드했는지 화면에서 알려 주기 위해
        buildConfigField("boolean", "HAS_MAPS_KEY", if (mapsApiKey.isBlank()) "false" else "true")
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
        buildConfig = true
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

    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)
    implementation(libs.play.services.location)
    // Play 서비스가 끌어오는 오래된 Fragment를 최신으로 (권한 요청 API 요구사항)
    implementation(libs.androidx.fragment)
}
