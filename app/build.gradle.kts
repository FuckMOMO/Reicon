plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.darkicongen.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.darkicongen.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // 真机侧载验证用：debug 包单独占一个 applicationId，
            // 不覆盖用户已装的正式版（正式版是作者私钥签名，装不上同一包名）。
            applicationIdSuffix = ".dev"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9+ 内置 Kotlin 支持，jvmTarget 由 compileOptions 决定
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.io)
    // Xposed API：LSPosed 钩子模块（仅编译期，运行时由框架提供）
    compileOnly(libs.xposed.api)
}
