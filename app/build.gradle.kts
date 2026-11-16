import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
    compileSdk = 36

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

    buildTypes {
        release {
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

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
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
