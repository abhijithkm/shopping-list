import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Release signing material comes only from Gradle properties (~/.gradle/gradle.properties or -P),
// never from the repository. If any is missing, release builds are refused instead of silently
// falling back to the debug key.
val uploadStoreFile = providers.gradleProperty("SL_UPLOAD_STORE_FILE")
val uploadStorePassword = providers.gradleProperty("SL_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = providers.gradleProperty("SL_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = providers.gradleProperty("SL_UPLOAD_KEY_PASSWORD")
val hasUploadKey = uploadStoreFile.isPresent && uploadStorePassword.isPresent &&
    uploadKeyAlias.isPresent && uploadKeyPassword.isPresent

android {
    namespace = "space.abhijithkm.shoppinglist"
    // compileSdk 37 is required by the current stable AndroidX libraries; targetSdk stays 36 (Play policy).
    compileSdk = 37

    defaultConfig {
        applicationId = "space.abhijithkm.shoppinglist"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasUploadKey) {
            create("release") {
                storeFile = file(uploadStoreFile.get())
                storePassword = uploadStorePassword.get()
                keyAlias = uploadKeyAlias.get()
                keyPassword = uploadKeyPassword.get()
            }
        }
    }

    // AppHub endpoints are public values (no secrets). Debug builds talk to the local fake AppHub
    // (backend/apphub/fake-server, reachable from the emulator as 10.0.2.2; it serves /{app}/... with no
    // /apphub prefix) unless -PSL_APPHUB_BASE_URL points elsewhere (a cloud dev app would be
    // https://132-226-190-156.sslip.io/apphub/); release builds talk to the cloud AppHub.
    // The base URL must end with "/".
    buildTypes {
        debug {
            buildConfigField(
                "String",
                "APPHUB_BASE_URL",
                "\"${providers.gradleProperty("SL_APPHUB_BASE_URL").getOrElse("http://10.0.2.2:8787/")}\"",
            )
            buildConfigField(
                "String",
                "APPHUB_APP",
                "\"${providers.gradleProperty("SL_APPHUB_APP").getOrElse("shoppinglistdev")}\"",
            )
        }
        release {
            buildConfigField("String", "APPHUB_BASE_URL", "\"https://132-226-190-156.sslip.io/apphub/\"")
            buildConfigField("String", "APPHUB_APP", "\"shoppinglist\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasUploadKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // The exported Room schemas are test assets so migrations can be tested from a real old database.
    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.reorderable)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockwebserver3)
}

// Refuse unsigned/debug-signed release artifacts: a missing key must fail the build loudly.
gradle.taskGraph.whenReady {
    val producesReleaseArtifact = allTasks.any {
        it.path.startsWith(":app:") && Regex("^(assemble|bundle|package)\\w*Release$").matches(it.name)
    }
    if (producesReleaseArtifact && !hasUploadKey) {
        throw GradleException(
            "Release build refused: upload key not configured. Set SL_UPLOAD_STORE_FILE, " +
                "SL_UPLOAD_STORE_PASSWORD, SL_UPLOAD_KEY_ALIAS and SL_UPLOAD_KEY_PASSWORD in " +
                "~/.gradle/gradle.properties (never in the repository).",
        )
    }
}
