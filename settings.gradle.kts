pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Standalone, this builds and tests the two modules on their own. TG9 and G9 include this
// repository as a git submodule at nine-core/ and point their own settings at the same modules,
// so this file is only read when working on nine-core by itself.
rootProject.name = "nine-core"
include(":core")
include(":kit")
