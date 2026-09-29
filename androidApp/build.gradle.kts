import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

apply(from = rootProject.file("gradle/build-version.gradle.kts"))
val buildNumber = extra["sharedVersionCode"] as Int
val updateHost = JsonSlurper().parse(rootProject.file("tools/update-host.json")) as Map<*, *>
val updateBaseUrl = updateHost["baseUrl"] as String
val updateManifestUrl = if (updateBaseUrl.isBlank()) "" else updateBaseUrl + updateHost["manifestName"]
val releaseKeystore = file(System.getenv("NOTEBOOKPLUSH_KEYSTORE_FILE")
    ?: "${System.getProperty("user.home")}/.android/notebookplush-release.jks")
val releaseStorePassword = providers.environmentVariable("NOTEBOOKPLUSH_KEYSTORE_PASSWORD")
    .orElse(providers.gradleProperty("notebookplushReleaseStorePassword")).orNull
val releaseKeyPassword = providers.environmentVariable("NOTEBOOKPLUSH_KEY_PASSWORD").orNull
    ?.takeIf { it.isNotBlank() } ?: releaseStorePassword
val canSignRelease = releaseKeystore.isFile && !releaseStorePassword.isNullOrBlank()

android {
    namespace = "com.notebookplush"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.notebookplush"
        minSdk = 24
        targetSdk = 37
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "UPDATE_BASE_URL", "\"$updateBaseUrl\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
    }
    signingConfigs {
        if (canSignRelease) create("release") {
            storeFile = releaseKeystore
            storePassword = releaseStorePassword
            keyAlias = providers.environmentVariable("NOTEBOOKPLUSH_KEY_ALIAS").getOrElse("notebookplush")
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        release {
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
        }
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".testing"
            matchingFallbacks += "debug"
        }
    }
    testBuildType = "qa"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sora.editor)
    implementation(libs.sora.textmate)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
