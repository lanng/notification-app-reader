import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

val localProperties: Properties = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                load(reader)
            }
        }
}

val c6PackageName = gradle.startParameter.projectProperties["c6PackageName"]
    ?: localProperties.getProperty("c6.packageName")
    ?: ""

val ownerApiOrigin = gradle.startParameter.projectProperties["ownerApiOrigin"] ?: ""

val releaseStoreFilePath = localProperties.getProperty("release.storeFile")?.takeIf(String::isNotBlank)
val releaseStorePassword = localProperties.getProperty("release.storePassword")?.takeIf(String::isNotBlank)
val releaseKeyAlias = localProperties.getProperty("release.keyAlias")?.takeIf(String::isNotBlank)
val releaseKeyPassword = localProperties.getProperty("release.keyPassword")?.takeIf(String::isNotBlank)
val hasReleaseSigning = releaseStoreFilePath != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

fun String.asJavaStringLiteral(): String = buildString {
    append('"')
    for (character in this@asJavaStringLiteral) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> {
                if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

android {
    namespace = "br.com.c6tag.diagnostico"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.tagalert.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "C6_PACKAGE_NAME", c6PackageName.asJavaStringLiteral())
        buildConfigField("String", "OWNER_API_ORIGIN", ownerApiOrigin.asJavaStringLiteral())
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFilePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.androidx.fragment)
    implementation(libs.gson)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Compose UI-test brings Espresso 3.5.0 transitively; pin a version compatible with API 36.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.work.testing)
}
