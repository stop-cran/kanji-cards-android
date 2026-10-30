plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

val verifyJvmBoundary by tasks.registering {
    doLast {
        listOf("compileClasspath", "runtimeClasspath", "testRuntimeClasspath").forEach { name ->
            configurations.getByName(name).resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                val group = artifact.moduleVersion.id.group
                check(!group.startsWith("androidx.") && !group.startsWith("com.android") &&
                    !group.startsWith("com.google.android") && group != "org.robolectric") {
                    "Android dependency in :core $name: ${artifact.moduleVersion.id}"
                }
            }
        }
    }
}

tasks.check {
    dependsOn(verifyJvmBoundary)
}
