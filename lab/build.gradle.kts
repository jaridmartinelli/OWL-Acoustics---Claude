import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Depth Anything V2 Small (Apache-2.0), int8 ONNX export from onnx-community; downloaded at build time, not committed.
val modelUrl = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_quantized.onnx"
val modelSha256 = "fcf51f1b230362b28690bb9d1809bf0431f29cad20534e3f589bd7285547f20d"
val modelDir = layout.buildDirectory.dir("generated/model-assets")

val fetchDepthModel by tasks.registering {
    val out = modelDir.map { it.file("depth_anything_v2_small_q8.onnx") }
    outputs.file(out)
    doLast {
        val f = out.get().asFile
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        if (f.exists() && sha(f.readBytes()) == modelSha256) return@doLast
        f.parentFile.mkdirs()
        val bytes = URI(modelUrl).toURL().openStream().use { it.readBytes() }
        check(sha(bytes) == modelSha256) { "Depth model checksum mismatch" }
        f.writeBytes(bytes)
    }
}

android {
    namespace = "com.owlacoustics.capturelab"
    compileSdk = 36

    defaultConfig {
        // Separate test app: never replaces or reads the main OWL app's data.
        applicationId = "com.owlacoustics.capturelab.dev"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-lab"
        // 64-bit ARM phones only (OnePlus Open is arm64); keeps the depth engine's native code from bloating the APK
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets["main"].assets.srcDir(modelDir)
    androidResources { noCompress += "onnx" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint { abortOnError = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

tasks.named("preBuild") { dependsOn(fetchDepthModel) }

dependencies {
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.arcore)
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.android)
}
