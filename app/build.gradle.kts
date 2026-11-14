import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "uk.co.magpie.instrument"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "uk.co.magpie.instrument"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 5
        versionName = "1.3.0"
    }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("tflite", "onnx") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("com.google.ai.edge.litert:litert:1.1.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// A clean Gradle build verifies that the reviewed, licensed model bytes are present.
val verifyBundledModel by tasks.registering {
    val expected = mapOf(
        "minc_materials23_int8.onnx" to "0222f661abc762bc0ef69a7eded939a921ab7e0f2174c37788891162f1d0f2c8",
        "minc_materials23_labels.txt" to "ef8d90b59508e8b5c79f07ef4b6811017a228d8960bbbaeee02a203bb796be90",
        "efficientdet_lite0.tflite" to "33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704",
        "coco_labels.txt" to "dc183f003fc753c4c43fae6fdf7f387559449573f13fa32e517fb7453fd380f1"
    )
    inputs.files(expected.keys.map { file("src/main/assets/models/$it") })
    doLast {
        expected.forEach { (name, checksum) ->
            val digest = MessageDigest.getInstance("SHA-256")
            file("src/main/assets/models/$name").inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(actual == checksum) { "Bundled model asset failed integrity check: $name" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyBundledModel) }
