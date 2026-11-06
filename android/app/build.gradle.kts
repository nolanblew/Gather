import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "ninja.takeya.gather"
    compileSdk = 35
    defaultConfig {
        applicationId = "ninja.takeya.gather"
        minSdk = 34
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 4
        versionName = "1.1.2"
        buildConfigField("String", "SERVER_URL", "\"https://gather.takeya.ninja/\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    val signingFile = file(System.getenv("GATHER_SIGNING_PROPERTIES") ?: "${System.getProperty("user.home")}/.codex/secrets/gather-android/release.properties")
    if (signingFile.exists()) {
        val properties = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("production") {
            storeFile = file(properties.getProperty("storeFile"))
            storePassword = properties.getProperty("storePassword")
            keyAlias = properties.getProperty("keyAlias")
            keyPassword = properties.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("production")
    }
    buildTypes { debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug"; buildConfigField("String", "SERVER_URL", "\"${providers.gradleProperty("gatherServerUrl").getOrElse("https://gather.takeya.ninja/")}\"") }; release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.compose.ui:ui:1.7.2")
    implementation("androidx.compose.foundation:foundation:1.7.2")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.window:window-testing:1.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.2")
}

tasks.configureEach {
    if (name in listOf("validateSigningRelease", "packageRelease", "bundleRelease")) doFirst {
        check(android.signingConfigs.findByName("production") != null) { "Release signing is required. Set GATHER_SIGNING_PROPERTIES to your private release.properties." }
    }
}



