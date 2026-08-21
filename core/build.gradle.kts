plugins {
    alias(libs.plugins.kotlin.jvm)
}

// No Android dependency anywhere in this module. That is enforced by the plugin choice, not by
// convention, and it is what makes the protocol testable on a laptop and reusable by :sim.
dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
