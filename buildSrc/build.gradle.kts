import org.gradle.plugin.use.PluginDependency

plugins {
    `kotlin-dsl`
}

fun pluginMarker(p: Provider<PluginDependency>): String =
    p.get().run { "$pluginId:$pluginId.gradle.plugin:$version" }

dependencies {
    implementation(pluginMarker(libs.plugins.kotlin.jvm))
    implementation(pluginMarker(libs.plugins.kotlin.spring))
    implementation(pluginMarker(libs.plugins.kotlin.jpa))
    implementation(pluginMarker(libs.plugins.spring.boot))
    implementation(pluginMarker(libs.plugins.spring.dependency.management))
}
