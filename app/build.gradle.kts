import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val homeEnvironment = Properties().apply {
    val file = rootProject.file("env/home.properties")
    require(file.isFile) { "Missing environment config: ${file.path}" }
    file.inputStream().use { load(it) }
}

fun environmentValue(name: String): String {
    val value = providers.gradleProperty(name).orNull ?: homeEnvironment.getProperty(name, "")
    require(value.isNotBlank()) { "Missing $name in env/home.properties or Gradle properties" }
    return value
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.myhome.remote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.myhome.remote"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "REMOTE_API_URL", buildConfigString(environmentValue("REMOTE_API_URL")))
        buildConfigField("String", "HOME_PAGE_URL", buildConfigString(environmentValue("HOME_PAGE_URL")))
        buildConfigField("String", "JELLYFIN_URL", buildConfigString(environmentValue("JELLYFIN_URL")))
        buildConfigField("String", "MATCHDAY_URL", buildConfigString(environmentValue("MATCHDAY_URL")))
        buildConfigField("String", "REQUESTS_URL", buildConfigString(environmentValue("REQUESTS_URL")))
        buildConfigField("String", "LAN_ADDRESS", buildConfigString(environmentValue("LAN_ADDRESS")))
        buildConfigField("String", "HOME_DNS_NAME", buildConfigString(environmentValue("HOME_DNS_NAME")))
        buildConfigField("String", "REMOTE_DNS_NAME", buildConfigString(environmentValue("REMOTE_DNS_NAME")))
    }

    buildTypes {
        getByName("release") {
            // Keep local release APKs installable as updates to the app installed from debug builds.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("io.coil-kt.coil3:coil-svg:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
