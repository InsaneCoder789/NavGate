plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.rohanc.navgate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rohanc.navgate"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Optional device build avoids shipping unused native ABIs to a storage-limited emulator.
        providers.gradleProperty("navgate.abi").orNull?.let { selectedAbi ->
            require(selectedAbi in setOf("arm64-v8a","armeabi-v7a","x86","x86_64"))
            ndk { abiFilters += selectedAbi }
        }
        // Where opted-in crash reports are posted. Blank (the default) means the option is hidden and nothing is ever sent.
        buildConfigField("String", "CRASH_REPORT_URL", "\"${(project.findProperty("navgate.crashUrl") as String?) ?: ""}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // The release key never lives in the repository: put these four lines in ~/.gradle/gradle.properties on the machine that builds the store
    // release (NAVGATE_STORE_FILE, NAVGATE_STORE_PASSWORD, NAVGATE_KEY_ALIAS, NAVGATE_KEY_PASSWORD). Without them the release build is unsigned.
    val releaseKey = listOf("NAVGATE_STORE_FILE", "NAVGATE_STORE_PASSWORD", "NAVGATE_KEY_ALIAS", "NAVGATE_KEY_PASSWORD").map { providers.gradleProperty(it).orNull }
    if (releaseKey.all { it != null }) signingConfigs.create("release") {
        storeFile = file(releaseKey[0]!!); storePassword = releaseKey[1]; keyAlias = releaseKey[2]; keyPassword = releaseKey[3]
    }
    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Shrunk and obfuscated for users: smaller download, faster start, harder to reverse. Rules are in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // The release build, signed with the debug key and installable next to the debug app, so the shrunk app can be tried on a phone.
        create("releaseCheck") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".check"
            matchingFallbacks += "release"
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
        }
    }
}

dependencies {
    // Installs the baseline profile (app/src/main/baseline-prof.txt): the screens and map code start compiled instead of being interpreted.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(project(":navigation-core"))
    implementation(project(":brouter"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    
    // ARCore
    implementation("com.google.ar:core:1.54.0")
    // On-device object detection (people, vehicles, animals) so the AR road line can pass behind them. Pretrained model in assets/models.
    implementation("com.google.mediapipe:tasks-vision:0.10.29")
    
    // MapLibre
    implementation("org.maplibre.gl:android-sdk:11.13.5")
    implementation("org.maplibre.compose:maplibre-compose:0.11.1")

    // Native GLB rendering for navigation vehicles, with transparent TextureView composition.
    implementation("com.google.android.filament:filament-android:1.66.0")
    implementation("com.google.android.filament:gltfio-android:1.66.0")

    // CameraX
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")

    // Real backdrop blur for the frosted-glass panels.
    implementation("dev.chrisbanes.haze:haze:1.6.10")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}
