plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}
android {
    namespace = "com.jarvis.assistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 33
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=ON") }
        }
        targetSdk = 35
        versionCode = 43
        versionName = "3.13"
    }
    ndkVersion = "29.0.13113456"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" }
    }
    buildFeatures { compose = true }
    androidResources { noCompress += "gguf" }
    chaquopy { defaultConfig { version = "3.12" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.apache-extras.beanshell:bsh:2.0b6")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
