plugins {
    id("com.android.library")
    kotlin("android")
}

// What TG9's "send photo or video" gallery and the G9 gallery app share: reading photos and
// videos through MediaStore, the newest-first rows with day headers, thumbnails, marks, and the
// zoomable picture. Built on :core like the apps themselves. Every app that includes it names the
// two modules :core and :kit.
android {
    namespace = "dev.g9.kit"
    compileSdk = 34

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        disable += "SetTextI18n"
        warningsAsErrors = true
        abortOnError = true
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
