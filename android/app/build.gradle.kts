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
        versionCode = 9
        versionName = "1.0.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
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
}
