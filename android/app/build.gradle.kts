import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val buildCommit =
    providers
        .gradleProperty("tagesschauCommit")
        .orElse(providers.environmentVariable("GITHUB_SHA"))
        .orElse("unknown")
        .get()
val buildDate =
    providers
        .gradleProperty("tagesschauBuildDate")
        .orElse(providers.environmentVariable("BUILD_DATE"))
        .orElse(Instant.now().toString())
        .get()

val configuredVersionCode =
    providers
        .gradleProperty("versionCode")
        .orElse("1")
        .map { value ->
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: error("versionCode must be a positive integer")
        }
        .get()
val configuredVersionName =
    providers.gradleProperty("versionName").orElse("0.1.0").get().also { name ->
        require(name.isNotBlank()) { "versionName must not be blank" }
    }

android {
    namespace = "dev.pschmitt.tagesschau"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.pschmitt.tagesschau"
        minSdk = 26
        targetSdk = 36
        versionCode = configuredVersionCode
        versionName = configuredVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BUILD_COMMIT", buildConfigString(buildCommit))
        buildConfigField("String", "BUILD_DATE", buildConfigString(buildDate))
    }

    buildTypes {
        named("debug") {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "tagesschau (debug)")
        }
        named("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            resValue("string", "app_name", "tagesschau")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    signingConfigs {
        named("debug") {
            System.getenv("CI_KEYSTORE_PATH")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("CI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CI_KEY_ALIAS")
                keyPassword = System.getenv("CI_KEY_PASSWORD")
            }
        }
    }

    buildTypes.named("release") { signingConfig = signingConfigs.getByName("debug") }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.cast)
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
