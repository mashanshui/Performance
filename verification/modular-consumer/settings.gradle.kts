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
        // 通过当前 Windows 用户目录寻找外部第三方本地制品，不写入机器专属绝对路径。
        val userMavenRepository = java.io.File(
            System.getenv("USERPROFILE") ?: System.getProperty("user.home"),
            ".m2/repository",
        )
        // 可由命令行覆盖的 Performance 发布仓库位置。
        val localRepository = providers.gradleProperty("performance.repo")
            .orElse(System.getenv("PERFORMANCE_MAVEN_REPO") ?: "")
        if (localRepository.get().isNotBlank()) {
            maven { url = uri(localRepository.get()) }
        } else {
            mavenLocal()
        }
        // 允许消费者解析主工程依赖的本地第三方组件，例如 Rhea 和 KOOM。
        maven {
            url = uri(userMavenRepository)
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "modular-consumer"
include(":crash-consumer")
include(":metrics-consumer")
include(":full-consumer")
