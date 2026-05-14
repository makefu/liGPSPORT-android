import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "de.syntaxfehler.ligpsport"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.syntaxfehler.ligpsport"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "de.syntaxfehler.ligpsport.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }

        // u-blox AssistNow Online developer token — used by the AGPS
        // pre-seed step in UploadPipeline. Empty = AGPS seeding is
        // skipped silently. Obtain a free token at
        // https://www.u-blox.com/en/assistnow-service-evaluation-token-request
        // and inject via the environment variable below; see
        // /home/makefu/r/ligpsport/docs/PROTOCOL.md §10.
        val agpsToken = System.getenv("LIGPSPORT_AGPS_TOKEN") ?: ""
        buildConfigField("String", "AGPS_TOKEN", "\"${agpsToken}\"")
    }

    // The release keystore is provided via environment variables when
    // available; otherwise the release APK is signed with the debug
    // keystore so the build still completes (useful for CI artefact
    // inspection — must not be distributed).
    val ksPath = System.getenv("KEYSTORE_PATH")
    val ksPass = System.getenv("KEYSTORE_PASSWORD")
    val keyAliasEnv = System.getenv("KEY_ALIAS")
    val keyPass = System.getenv("KEY_PASSWORD")
    val hasReleaseKeystore =
        ksPath != null && ksPass != null && keyAliasEnv != null && keyPass != null

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = keyAliasEnv
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        animationsDisabled = true
    }

    sourceSets {
        // src/main/proto/ is the protobuf-gradle default location; no
        // explicit srcDir wiring needed.
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }
}

protobuf {
    protoc {
        artifact = libs.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") { option("lite") }
                id("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.osmdroid.android)
    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.intents)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
