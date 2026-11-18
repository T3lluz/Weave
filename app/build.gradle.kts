import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.t3lluz.weave"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.t3lluz.weave"
        minSdk = 26
        targetSdk = 35
        // CI passes these (the commit count), so a release never needs a version-bump commit. A
        // build made here counts the commits too, so the newest release never offers itself as
        // an update to a test build of the same code or newer.
        val commits = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: commits.get()
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1.${commits.get()}-dev"

        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        }
        val anthropicKey = localProperties.getProperty("ANTHROPIC_API_KEY", "")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicKey\"")
    }

    signingConfigs {
        // A tester keystore kept in the repo, so every release installs over the last one
        // (same certificate and a higher versionCode is an update, not a reinstall).
        create("tester") {
            storeFile = file("tester.jks")
            storePassword = "weave-tester"
            keyAlias = "weave"
            keyPassword = "weave-tester"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("tester")
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("tester")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            optIn.add("kotlin.RequiresOptIn")
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.core.ktx)
    // Claude's sign-in page opens in a Custom Tab
    implementation(libs.browser)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    // Claude's sign-in reads its token answers with it
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")

    // The provider logos in the AI picker (SVGs in assets/models)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    // Frosted glass over the laptop's picture
    implementation(libs.haze)

    // The background check for a new release (in-app updates)
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; the real one lets the parsers be tested.
    testImplementation("org.json:json:20240303")
}
