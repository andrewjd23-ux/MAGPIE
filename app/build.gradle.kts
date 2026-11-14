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
        versionCode = 3
        versionName = "1.1.0"
    }
    buildFeatures { compose = true }
    androidResources { noCompress += "tflite" }
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
    testImplementation("junit:junit:4.13.2")
}

// A clean Gradle build verifies that the reviewed, licensed model bytes are present.
val verifyBundledModel by tasks.registering {
    val expected = mapOf(
        "efficientdet_lite0.tflite" to "33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704",
        "coco_labels.txt" to "dc183f003fc753c4c43fae6fdf7f387559449573f13fa32e517fb7453fd380f1"
    )
    inputs.files(expected.keys.map { file("src/main/assets/models/$it") })
    doLast {
        expected.forEach { (name, checksum) ->
            val bytes = file("src/main/assets/models/$name").readBytes()
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            check(actual == checksum) { "Bundled model asset failed integrity check: $name" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyBundledModel) }
