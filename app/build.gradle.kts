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
        versionCode = 16
        versionName = "1.3.7"
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

    testOptions {
        // 纯逻辑类（DNS/规则/JSON）在 JVM 单测里直接跑；
        // android.util.Log 等桩方法返回默认值，避免 "not mocked" 抛错。
        unitTests.isReturnDefaultValues = true
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

    // 只用于本机 JVM 单测，不进 APK。
    testImplementation("junit:junit:4.13.2")
}

// —— JVM 单测运行支持 ——
// 症状：7 个用例全部 initializationError → ClassNotFoundException。
// 根因：Gradle 8.9 在 classpath 过长时把测试 JVM 的参数写成 @argfile（UTF-8 编码），
//       而 java.exe 读 @argfile 用的是系统编码（中文 Windows 为 GBK），
//       D:\AI库\... 被解成乱码，项目内的 class 目录在测试 JVM 里全部失效。
//       同一份 classpath 直接以 -cp 传参则完全正常（已实测）。
// 处理：把解析好的 classpath 落成文本文件，交给 run-tests.sh 用 -cp 直接跑。
//       工程若迁到纯 ASCII 路径，标准入口 testDebugUnitTest 同样可用。
tasks.register("writeUnitTestClasspath") {
    group = "verification"
    description = "导出 debug 单测完整 classpath，供 run-tests.sh 绕开 @argfile 编码问题"
    dependsOn("compileDebugUnitTestKotlin", "compileDebugUnitTestJavaWithJavac")
    val outFile = layout.buildDirectory.file("unitTestClasspath.txt")
    doLast {
        val javacRoot = layout.buildDirectory.dir("intermediates/javac/debugUnitTest").get().asFile
        // AGP 8.7 把 javac 产物放在 intermediates/javac/<variant>/<taskName>/classes，
        // 低版本是 intermediates/javac/<variant>/classes，这里按目录名找，避免绑死版本。
        val javacStub = javacRoot.walkTopDown()
            .firstOrNull { it.isDirectory && it.name == "classes" }
            ?: javacRoot
        val kotlinTest = layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest").get().asFile
        val kotlinMain = layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFile
        // 顺序有意义：android.util.Log 的测试桩必须排在 android.jar 之前。
        val head = listOf(javacStub, kotlinTest, kotlinMain).filter { it.isDirectory }
        val cp = files(head)
            .plus(configurations.getByName("debugUnitTestRuntimeClasspath"))
            .files
            .joinToString(System.getProperty("path.separator"))
        outFile.get().asFile.writeText(cp)
        println("unitTestClasspath -> ${outFile.get().asFile.absolutePath}")
    }
}
