pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
        // KSP is published to the Plugin Portal and Maven Central, never Google Maven.
        excludeGroup("com.google.devtools.ksp")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    exclusiveContent {
      forRepository {
        ivy {
          name = "mpvlibGitHubReleases"
          // The patched-FongMi workflow (.github/workflows/build-fongmi-patched.yml)
          // builds mpvlib from source and passes -PmpvlibReleasesUrl=<local dir>
          // with this same layout; the property is unset everywhere else, so
          // normal builds keep resolving the upstream release.
          url = uri(
            gradle.startParameter.projectProperties["mpvlibReleasesUrl"]
              ?: "https://github.com/Riteshp2001/mpvlibAndroid/releases/download"
          )
          patternLayout {
            artifact("v[revision]/[artifact]-[revision].[ext]")
          }
          metadataSources {
            artifact()
          }
        }
      }
      filter {
        includeGroup("app.gyrolet.mpvlib")
      }
    }
    maven(url = "https://www.jitpack.io") {
      content {
        // Only use JitPack for specific dependencies to avoid unnecessary checks
        includeGroup("io.github.abdallahmehiz")
        includeGroup("com.github.abdallahmehiz")
        includeGroup("com.github.K1rakishou")
        includeGroup("com.github.marlboro-advance")
        includeGroup("com.github.thegrizzlylabs")
      }
    }
  }
}

rootProject.name = "mpvRx"
include(":app")
