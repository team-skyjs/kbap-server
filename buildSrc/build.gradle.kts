import org.gradle.plugin.use.PluginDependency

plugins {
    // 미리 컴파일된 .gradle.kts 컨벤션 플러그인을 작성/적용할 수 있게 한다.
    `kotlin-dsl`
}

// 컨벤션 플러그인이 `id("...")` 로 적용할 서드파티 Gradle 플러그인을 classpath 에 올린다.
// 버전은 루트 카탈로그(libs.plugins.*)에서 가져오고, 플러그인 마커 좌표로 변환해 의존한다.
fun pluginMarker(p: Provider<PluginDependency>): String =
    p.get().run { "$pluginId:$pluginId.gradle.plugin:$version" }

dependencies {
    implementation(pluginMarker(libs.plugins.kotlin.jvm))
    implementation(pluginMarker(libs.plugins.kotlin.spring))
    implementation(pluginMarker(libs.plugins.kotlin.jpa))
    implementation(pluginMarker(libs.plugins.spring.boot))
    implementation(pluginMarker(libs.plugins.spring.dependency.management))
}
