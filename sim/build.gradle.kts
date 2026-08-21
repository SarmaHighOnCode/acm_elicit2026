plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "com.setu.mesh.sim.MainKt"
}

tasks.test {
    useJUnitPlatform()
}
