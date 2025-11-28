import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("kotlin-parcelize")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            // Cactus Kotlin SDK (Android)
            implementation("com.cactuscompute:cactus:1.0.2-beta")
        }
        iosMain.dependencies {
            // Cactus Kotlin SDK (iOS)
            implementation("com.cactuscompute:cactus:1.0.2-beta")
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.materialIconsExtended)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(projects.shared)
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.6")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            implementation("com.russhwolf:multiplatform-settings-no-arg:1.1.1")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "online.visabud.app.visabud_multiplatform"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "online.visabud.app.visabud_multiplatform"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Convenience iOS build aliases to avoid IDE CidrBuild path
// Use: ./gradlew :composeApp:buildIosSim, :composeApp:buildIosDevice, or :composeApp:buildIosXCFramework
tasks.register("buildIosSim") { dependsOn("linkDebugFrameworkIosSimulatorArm64") }
tasks.register("buildIosDevice") { dependsOn("linkDebugFrameworkIosArm64") }
tasks.register("buildIosXCFramework") { dependsOn("assembleXCFramework") }

dependencies {
    debugImplementation(compose.uiTooling)
}

