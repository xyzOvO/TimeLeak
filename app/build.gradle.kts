plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chengliu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chengliu.app"
        minSdk = 26
        // 保持 33：Android 14+ 对 targetSdk>=34 强制前台服务类型声明，
        // 本地工具类应用无需上架，避开这层兼容分支更稳、也更轻。
        targetSdk = 33
        versionCode = 9
        versionName = "1.3.0"
    }

    buildTypes {
        release {
            // 与 debug 共用同一把本机密钥，保证本地版与后续升级包可互相覆盖。
            // 不开启混淆：本项目无反射依赖，且保持源码可读便于自行审计。
            isMinifyEnabled = false
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Kotlin 1.8 起 stdlib 已合并 jdk7/jdk8 的内容，这两个历史传递依赖无需再解析。
// 它们会把离线构建指向缓存里不存在的 1.9.10，故显式排除。
configurations.configureEach {
    exclude(mapOf("group" to "org.jetbrains.kotlin", "module" to "kotlin-stdlib-jdk7"))
    exclude(mapOf("group" to "org.jetbrains.kotlin", "module" to "kotlin-stdlib-jdk8"))
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // 网络请求一律用 Android 自带的 HttpURLConnection，
    // 不引第三方网络库，也不引任何统计、崩溃、推送 SDK。
}
