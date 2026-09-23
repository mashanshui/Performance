pluginManagement {
    repositories {
        mavenLocal()
        // 显式加入 Windows 用户 Maven 仓库，兼容沙箱中 Java user.home 与 USERPROFILE 不一致。
        maven {
            url = uri(
                java.io.File(
                    System.getenv("USERPROFILE") ?: System.getProperty("user.home"),
                    ".m2/repository",
                ),
            )
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
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
        mavenLocal()
        // 本地发布的 Rhea/KOOM 及其传递依赖从用户仓库读取。
        maven {
            url = uri(
                java.io.File(
                    System.getenv("USERPROFILE") ?: System.getProperty("user.home"),
                    ".m2/repository",
                ),
            )
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "Performance"
include(":app")
include(":performance-core")
include(":performance-transport")
include(":performance-native-tools")
include(":performance-metrics")
include(":performance-jank")
include(":performance-leak")
include(":performance-crash")
include(":performance-sdk")
