import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from keystore.properties (not committed). Without it, release builds are
// signed with the debug key so anyone can still build.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "dev.jaronwilson.headphones"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.jaronwilson.headphones"
        minSdk = 31
        targetSdk = 35
        versionCode = 38
        versionName = "1.11.0"
        // ML Kit's translate/language-id native libraries are large; Android 12+ phones are 64-bit ARM
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // the EQ bands and presets: one shared file for the phone, the laptop panel and Windows. Only the JSON is
    // packaged (copySharedAssets below), not shared/*.py or its __pycache__.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/shared-assets"))
    // Filtered hearing's speaker model: downloaded at build time (see fetchModels below), not committed
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/fh-models"))
    // ONNX Runtime's native library is 36 MB; compressed in the APK it is about a third of that (the phone
    // unpacks it once at install)
    packaging { jniLibs { useLegacyPackaging = true } }
    testOptions.unitTests.all {
        it.systemProperty("fh.models", layout.buildDirectory.dir("generated/fh-models/models").get().asFile.absolutePath)
        it.systemProperty("fh.dfn", file("src/main/assets/models/dfn3_stream.onnx").absolutePath)
        it.systemProperty("fh.testdata", rootProject.file("testdata").absolutePath)
        it.maxHeapSize = "2g"
    }
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // Lets a normal (non-root) app reach Android's hidden classic-L2CAP socket, the same way
    // LibrePods does on phones whose Bluetooth stack allows it.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // Activity Recognition Transition API for Smart adaptive (phone sensors, no mic)
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Live Translation, all on-device: translation models (downloaded per language) and language ID (bundled)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    // Last seen map: OpenStreetMap tiles, no API key, tiles cached on the phone
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Filtered hearing's neural engine: DeepFilterNet3 and CAM++ on ONNX Runtime (CPU)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    testImplementation("junit:junit:4.13.2")
    // the same ONNX Runtime API on the PC, so the unit tests run the real models
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.32.0")
}

// Unit tests run on the PC: use the desktop ONNX Runtime there, not the Android one.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

/*
 * CAM++ speaker embedding (3D-Speaker, Alibaba, Apache-2.0; trained on VoxCeleb), as converted to ONNX by
 * the sherpa-onnx project (k2-fsa, Apache-2.0). 29.6 MB, so it is fetched here instead of committed, and the
 * build fails if its SHA-256 doesn't match. Licence and source: android/app/src/main/assets/models/NOTICE.txt.
 */
val fetchModels by tasks.registering {
    val dir = layout.buildDirectory.dir("generated/fh-models/models")
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
    val sha = "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b"
    outputs.dir(dir)
    doLast {
        val f = dir.get().file("campplus_en_voxceleb.onnx").asFile
        fun sum(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (f.exists() && sum(f) == sha) return@doLast
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")
        logger.lifecycle("Downloading the CAM++ speaker model (29.6 MB)...")
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val got = sum(tmp)
        if (got != sha) { tmp.delete(); throw GradleException("CAM++ model checksum mismatch: $got") }
        tmp.renameTo(f)
    }
}
// shared/ also holds the laptop's and Windows' Python; the APK gets only the shared JSON data
val copySharedAssets by tasks.registering(Sync::class) {
    from("../../shared") { include("*.json") }
    into(layout.buildDirectory.dir("generated/shared-assets"))
}
tasks.named("preBuild") { dependsOn(fetchModels, copySharedAssets) }
tasks.withType<Test>().configureEach { dependsOn(fetchModels) }
