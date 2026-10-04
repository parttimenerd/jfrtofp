
group = "me.bechberger"
description = "Converting JFR files to Firefox Profiler profiles"

fun properties(key: String) = project.findProperty(key).toString()

repositories {
    mavenCentral()
    gradlePluginPortal()
    mavenLocal()
}

plugins {
    id("com.gradleup.shadow") version "8.3.11"
    pmd
    `maven-publish`
    application
    id("java-library")
    id("signing")
    id("com.gradleup.nmcp") version "0.1.5"
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
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

// WASM entry-point files (JFRParser, CJFRParser, WebMain) use @JS / JSObject from
// org.graalvm.webimage.api — a module that ships with GraalVM 25 as a named jmod.
// The stubs in src/main/java/org/graalvm/webimage/ allow compilation on non-GraalVM JDKs,
// but conflict when GraalVM 25 is the active JDK.
// Solution: exclude both the stubs and the WASM entry points from the regular build.
// The published JAR only needs the converter library classes (JFRConverter, Processor, …).
sourceSets.main {
    java {
        exclude("org/graalvm/webimage/**")
        exclude("me/bechberger/jfrtofp/JFRParser.java",
                "me/bechberger/jfrtofp/CJFRParser.java",
                "me/bechberger/jfrtofp/WebMain.java")
    }
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

// Exclude GraalVM webimage stubs from the published JAR — the stubs live in
// src/main/java only to allow local compilation without GraalVM toolchain.
// The real org.graalvm.webimage.api is provided by --tool:svm-wasm at WASM build time.
tasks.withType<Jar> {
    exclude("org/graalvm/webimage/**")
}

apply { plugin("com.gradleup.shadow") }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("info.picocli:picocli:4.7.7")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("io.btrace:jafar-parser:0.27.0")
    implementation("me.bechberger:condensed-data:0.1.4")

    // GraalVM Web Image annotations — only needed at compile time for @JS, @JS.Coerce
    compileOnly("org.graalvm.sdk:nativeimage:25.0.0")
}

// Clone (or update) condensed-data and install it to mavenLocal so the dependency above resolves.
val condensedDataDir = rootDir.resolve("condensed-data")

val cloneOrUpdateCondensedData by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Clone or update parttimenerd/condensed-data"
    outputs.dir(condensedDataDir)
    if (condensedDataDir.resolve(".git").exists()) {
        commandLine("git", "-C", condensedDataDir.absolutePath, "pull", "--ff-only")
    } else {
        commandLine("git", "clone", "https://github.com/parttimenerd/condensed-data.git",
            condensedDataDir.absolutePath)
    }
}

val installCondensedData by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Build condensed-data and install to mavenLocal"
    dependsOn(cloneOrUpdateCondensedData)
    workingDir(condensedDataDir)
    commandLine("mvn", "-q", "install", "-Dmaven.test.skip=true", "-P!jmc-test")
    inputs.dir(condensedDataDir.resolve("src"))
    inputs.file(condensedDataDir.resolve("pom.xml"))
    outputs.file(condensedDataDir.resolve("target/condensed-data-0.1.4.jar"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.named("compileJava") { dependsOn(installCondensedData) }

application {
    mainClass.set("me.bechberger.jfrtofp.Main")
}

tasks.register<Copy>("copyHooks") {
    from("bin/pre-commit")
    into(".git/hooks")
}

tasks.findByName("build")?.dependsOn(tasks.findByName("copyHooks")!!)

// Large-file OOM acceptance test. Run with: ./gradlew largeFileTest -PrunLarge=true
if (project.hasProperty("runLarge")) {
    tasks.register<JavaExec>("largeFileTest") {
        dependsOn("shadowJar")
        group = "verification"
        description = "Convert large JFR files under -Xmx512m to verify no OOM"
        classpath = files(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().archiveFile)
        mainClass.set("me.bechberger.jfrtofp.Main")
        val benchDir = "/Users/i560383_1/code/experiments/condensed-data/benchmark"
        val largeJfr = "$benchDir/renaissance-all_gc_G1.jfr"
        val outFile = layout.buildDirectory.file("large-test-out.json.gz").get().asFile.absolutePath
        args = listOf(largeJfr, "-o", outFile)
        jvmArgs = listOf("-Xmx512m")
        doLast {
            val sizeMb = file(outFile).length() / 1_048_576.0
            println("largeFileTest PASSED — output %.1f MB".format(sizeMb))
            file(outFile).delete()
        }
    }
}

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
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/parttimenerd/jfrtofp")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: properties("gpr.user")
                password = System.getenv("GITHUB_TOKEN") ?: properties("gpr.token")
            }
        }
    }
}

nmcp {
    centralPortal {
        username = properties("sonatypeTokenUsername")
        password = properties("sonatypeToken")
        publishingType = "AUTOMATIC"
    }
}

signing {
    val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
    if (signingKey != null) {
        useInMemoryPgpKeys(
            signingKey,
            providers.gradleProperty("signingInMemoryKeyPassword").orNull,
        )
        sign(publishing.publications["mavenJava"])
    }
}
