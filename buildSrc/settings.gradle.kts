// buildSrc 는 별도 빌드라 메인 빌드의 버전 카탈로그를 자동으로 보지 못한다.
// 루트의 gradle/libs.versions.toml 을 가져와 컨벤션 플러그인에서 같은 버전을 쓰게 한다.
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
