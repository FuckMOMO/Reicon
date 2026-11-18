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
        // 本机离线镜像：libsu（jitpack）与 xposed api（api.xposed.info）在该网络下 TLS 握手失败，
        // 已按 maven 布局落到本地仓库（见 docs/BUILD.md），解析优先级放在最前。
        maven { url = uri("file:///C:/Users/Carrot/Documents/deepseek-harness/default-workspace/work/darkicon/m2repo") }
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://api.xposed.info")
    }
}

rootProject.name = "DarkIconGen"
include(":app")
