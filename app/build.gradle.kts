import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

val openAiApiKey = localProperties.getProperty("OPENAI_API_KEY").orEmpty().trim()
    .ifBlank { System.getenv("OPENAI_API_KEY").orEmpty().trim() }
val openAiModel = localProperties.getProperty("OPENAI_MODEL").orEmpty().trim()
    .ifBlank { System.getenv("OPENAI_MODEL").orEmpty().trim() }
    .ifBlank { "gpt-5.6-luna" }

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.hwanghj09.sonju"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hwanghj09.sonju"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OPENAI_MODEL", openAiModel.asBuildConfigString())
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "OPENAI_API_KEY", openAiApiKey.asBuildConfigString())
        }
        release {
            buildConfigField("String", "OPENAI_API_KEY", "".asBuildConfigString())
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
