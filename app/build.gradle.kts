plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.snakesan.neonflux"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.snakesan.neonflux"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 1. Configure the CMake flags inside DefaultConfig
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        prefab = true // <--- Required for Oboe
    }

    // 2. Link the CMake build script here
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // NATIVE AUDIO LIB
    implementation(libs.oboe)

    // Play Services
    implementation(libs.play.services.wearable)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.core.splashscreen)

    implementation(libs.activity.compose)
    implementation(libs.ui)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)

    // Wear OS Compose
    implementation(libs.compose.material)
    implementation(libs.compose.foundation)

    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}