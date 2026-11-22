plugins {
    id("com.android.library")
    kotlin("android") version "1.9.24"
}

android {
    namespace = "life.furrer.nine.contacts"
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
        baseline = file("lint-baseline.xml")
    }

    // FakeContactsProvider is needed by C9's own tests too (ContactEditActivityTest exercises
    // ContactEditActivity, which stays in c9:app, against it) — a testFixtures source set is the
    // standard way to share a test double across module boundaries without shipping it in the
    // production artifact.
    testFixtures {
        enable = true
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation(testFixtures(project(":contacts")))
}

// Robolectric reflectively reaches into JDK internals (e.g. FileDescriptor) that JDK 17+'s module
// system blocks by default — these opens are Robolectric's own documented requirement, needed
// only for running tests, not for building the module itself.
tasks.withType<Test> {
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}
