
group = "me.bechberger"
description = "Converting JFR files to Firefox Profiler profiles"

inner class ProjectInfo {
    val longName = "JFR to Firefox Profiler converter"
    val website = "https://github.com/parttimenerd/jfrtofp"
    val scm = "git@github.com:parttimenerd/$name.git"
}

fun properties(key: String) = project.findProperty(key).toString()

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
    gradlePluginPortal()
}

plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm") version "1.7.10"
    kotlin("plugin.serialization") version "1.7.10"

    id("com.github.johnrengelman.shadow") version "7.1.2"

    // id("io.gitlab.arturbosch.detekt") version "1.21.0"
    pmd

    //id("org.jlleitschuh.gradle.ktlint") version "11.0.0"

    `maven-publish`

    // Apply the application plugin to add support for building a CLI application in Java.
    application

    id("java-library")
    id("signing")
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
}

pmd {
    isConsoleOutput = true
    toolVersion = "6.21.0"
    rulesMinimumPriority.set(5)
    ruleSets = listOf("category/java/errorprone.xml", "category/java/bestpractices.xml")
}

java {
    withJavadocJar()
    withSourcesJar()
}

apply { plugin("com.github.johnrengelman.shadow") }

/*detekt {
    buildUponDefaultConfig = true // preconfigure defaults
    config = files("$rootDir/config/detekt/detekt.yml")
    autoCorrect = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "1.8"
}
tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "1.11"
}*/

dependencies {
    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.10"))

    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.10")

    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")

    // Use the Kotlin JUnit integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.8.10")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")
    implementation("info.picocli:picocli:4.7.3")
    implementation("org.jline:jline-reader:3.23.0")
    implementation("org.ow2.asm:asm:9.4")
}

tasks.test {
    useJUnitPlatform()
}

application {
    // Define the main class for the application.
    mainClass.set("me.bechberger.jfrtofp.MainKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

tasks.register<Copy>("copyHooks") {
    //from("bin/pre-commit")
    //into(".git/hooks")
}

tasks.findByName("build")?.dependsOn(tasks.findByName("copyHooks"))

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            pom {
                name.set("jfrtofp")
                packaging = "jar"
                description.set(project.description)
                inceptionYear.set("2022")
                url.set("https://github.com/parttimenerd/jfrtofp")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("parttimenerd")
                        name.set("Johannes Bechberger")
                        email.set("me@mostlynerdless.de")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/parttimenerd/jfrtofp")
                    developerConnection.set("scm:git:https://github.com/parttimenerd/jfrtofp")
                    url.set("https://github.com/parttimenerd/jfrtofp")
                }
            }
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "Sonatype"
            url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            credentials {
                username = properties("sonatypeUsername")
                password = properties("sonatypePassword")
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}
