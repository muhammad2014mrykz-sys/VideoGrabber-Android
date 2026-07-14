pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // youtubedl-android (yt-dlp + ffmpeg for Android) is published on
        // Maven Central under the io.github.junkfood02 group.
        mavenCentral()
    }
}

rootProject.name = "VideoGrabber"
include(":app")
