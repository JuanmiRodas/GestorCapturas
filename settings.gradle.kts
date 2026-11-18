pluginManagement {
    repositories {
        google()
        maven("https://repo1.maven.org/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven(uri("local-repo"))
        google()
        maven("https://repo1.maven.org/maven2/")
        mavenCentral()
    }
}

rootProject.name = "ScreenshotManager"
include(":app")
