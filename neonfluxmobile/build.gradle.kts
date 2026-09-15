
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.snakesan.neonflux"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.snakesan.neonflux"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    // Embeds the watch module's APK in this module's AAB so one Play
    // listing installs both the phone app and, on a paired Wear OS device,
    // the watch app - no separate listing/applicationId needed since both
    // modules already share com.snakesan.neonflux.
    wearApp(project(":app"))

    implementation(libs.play.services.wearable)
    implementation(libs.core.ktx)
    implementation(libs.compose.foundation) // Provides core Wear Compose layout building blocks
    implementation(libs.compose.material)   // Provides Wear-specific Material components like Theme, Text, etc.
    implementation(libs.wear.tooling.preview)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    // Icons.Filled.Accessibility / Icons.Filled.Palette live here, not in the
    // small curated core icon set.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

}