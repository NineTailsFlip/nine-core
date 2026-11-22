plugins {
    id("com.android.library")
    kotlin("android")
}

// Updates from GitHub Releases, for every NineTailsFlip app: a daily check of the latest
// release's update.json (written by the release workflow), a prompt on the app's main screen, and
// the download and install through Android's own package installer.
android {
    namespace = "io.github.ninetailsflip.update"
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    testImplementation("org.robolectric:robolectric:4.13")
}

tasks.withType<Test> {
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}
