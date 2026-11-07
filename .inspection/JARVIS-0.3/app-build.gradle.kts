plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace="com.jarvis.assistant"
 compileSdk=35
 defaultConfig { applicationId="com.jarvis.assistant"; minSdk=26; targetSdk=35; versionCode=2; versionName="0.2" }
 buildFeatures { compose=true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 debugImplementation("androidx.compose.ui:ui-tooling")
}
