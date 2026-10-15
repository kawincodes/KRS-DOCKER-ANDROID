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
        mavenCentral()
        // Termux terminal-emulator / terminal-view (Apache-2.0), built by JitPack.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.termux.termux-app")
                // libadb-android (ADB pairing/TLS client) and its SPAKE2 dependency.
                includeGroup("com.github.MuntashirAkon")
                includeGroup("com.github.MuntashirAkon.spake2-java")
            }
        }
    }
}

rootProject.name = "KRS Dockers"
include(":app")
