import java.io.File
import java.util.Properties
import java.security.MessageDigest
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val signingProperties = Properties().apply {
    val signingPropertiesFile = rootProject.file("signing.properties")
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(environmentName: String, propertyName: String): String? =
    System.getenv(environmentName)?.takeIf { it.isNotBlank() }
        ?: signingProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = releaseSigningValue("ANDROID_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = releaseSigningValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = releaseSigningValue("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = releaseSigningValue("ANDROID_KEY_PASSWORD", "keyPassword")
val releaseStoreFile = releaseStoreFilePath?.let { path ->
    File(path).let { if (it.isAbsolute) it else rootProject.file(path) }
}
val releaseSigningConfigured = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it != null }

android {
    namespace = "com.codex.quota"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codex.quota"
        minSdk = 26
        targetSdk = 35
        versionCode = 38
        versionName = "1.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }
}

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Validates the external credentials required to sign release builds."

    doLast {
        val missingValues = buildList {
            if (releaseStoreFilePath == null) add("storeFile / ANDROID_KEYSTORE_FILE")
            if (releaseStorePassword == null) add("storePassword / ANDROID_KEYSTORE_PASSWORD")
            if (releaseKeyAlias == null) add("keyAlias / ANDROID_KEY_ALIAS")
            if (releaseKeyPassword == null) add("keyPassword / ANDROID_KEY_PASSWORD")
        }

        if (missingValues.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing: ${missingValues.joinToString()}. " +
                    "Provide them in the root signing.properties file or as environment variables."
            )
        }
        if (releaseStoreFile?.isFile != true) {
            throw GradleException("The configured release keystore file does not exist or is not a file.")
        }
    }
}

tasks.configureEach {
    val packagesRelease = name.contains("Release") &&
        (name.startsWith("assemble") || name.startsWith("bundle") || name.startsWith("package"))
    if (packagesRelease) {
        dependsOn(validateReleaseSigning)
    }
}

// Official Android SDK, pinned to the release asset's SHA-256. No model binary in the APK.
val sherpaAar = layout.buildDirectory.file("dependencies/sherpa-onnx-1.13.8.aar")
val downloadSherpaAar = tasks.register("downloadSherpaAar") {
    outputs.file(sherpaAar)
    doLast {
        val target = sherpaAar.get().asFile
        val expected = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        if (target.isFile && digest(target) == expected) return@doLast
        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, target.name + ".partial")
        try {
            val connection = URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar").toURL().openConnection()
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.getInputStream().use { input -> temporary.outputStream().use { out ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; check(total <= 64L * 1024 * 1024); out.write(buffer, 0, n) }
            } }
            check(digest(temporary) == expected) { "Sherpa SDK checksum mismatch" }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)

    // Compose BOM & UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    // Native Markdown replies (no WebView or remote image loader).
    implementation(libs.markwon.core)
    implementation(libs.markwon.tables)
    // Offline dictation fallback for devices whose system recognizer rejects third-party apps.
    implementation(files(sherpaAar).builtBy(downloadSherpaAar))

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Jetpack Glance Widgets
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Security Crypto
    implementation(libs.androidx.security.crypto)

    // OkHttp & Serialization & Coroutines
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)

    // Android Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
