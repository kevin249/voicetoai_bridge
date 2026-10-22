plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "io.github.kevin249.voicetoai"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.kevin249.voicetoai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "onnx" }
    buildTypes { release { isMinifyEnabled = false } }
    lint { abortOnError = true }
}
dependencies {
    implementation(files("libs/sherpa-onnx-1.12.14.aar"))
    testImplementation("junit:junit:4.13.2")
}
tasks.register("verifyVoiceAssets") {
    doLast {
        check(file("libs/sherpa-onnx-1.12.14.aar").isFile &&
            file("src/main/assets/kws/encoder.int8.onnx").isFile) {
            "Run python3 tools/prepare_deps.py from the repository root first."
        }
    }
}
tasks.named("preBuild").configure { dependsOn("verifyVoiceAssets") }
