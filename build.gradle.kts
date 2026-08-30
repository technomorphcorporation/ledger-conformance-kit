plugins {
    `java-library`
    `maven-publish`
    signing
}

allprojects {
    group = "com.technomorphcorporation.lck"
    version = providers.gradleProperty("version").getOrElse("1.0.0-SNAPSHOT")
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        // The toolchain is always 21 — that is what builds the project and runs the suite.
        // The BYTECODE TARGET is per-module, below.
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
        withSourcesJar()
    }

    dependencies {
        // The BOM must be on the test COMPILE classpath, not just runtime, or versionless
        // catalog aliases fail to resolve. This is the fix for:
        //   "Could not find org.junit.jupiter:junit-jupiter-api:."
        add("testImplementation", platform(rootProject.libs.junit.bom))
        // Required from Gradle 9; on 8.x the bundled launcher is old enough to break
        // against JUnit 5.12+. Correct on both lines.
        add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        // lck-spi targets 17 so a client still on Java 17 can implement LedgerAdapter.
        // Everything else needs 21 for Executors.newVirtualThreadPerTaskExecutor().
        options.release.set(if (project.name == "lck-spi") 17 else 21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-this-escape", "-Xlint:-serial"))
    }

    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false      // reproducible builds
        isReproducibleFileOrder = true
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Automatic-Module-Name" to "com.technomorphcorporation." + project.name.replace('-', '.')
            )
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("lck.seed", providers.gradleProperty("lck.seed").getOrElse("42"))
        maxHeapSize = "2g"
        testLogging { events("passed", "skipped", "failed") }
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("Executable correctness invariants for systems that move money")
                    url.set("https://github.com/technomorphcorporation/ledger-conformance-kit")
                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    developers { developer { id.set("manjulbhakri"); name.set("Manjul Bhakri") } }
                    scm { url.set("https://github.com/technomorphcorporation/ledger-conformance-kit") }
                }
            }
        }
    }

    configure<SigningExtension> {
        setRequired({ !version.toString().endsWith("SNAPSHOT") && gradle.taskGraph.hasTask("publish") })
        providers.gradleProperty("signingKey").orNull?.let {
            useInMemoryPgpKeys(it, providers.gradleProperty("signingPassword").orNull)
        }
        sign(extensions.getByType<PublishingExtension>().publications["maven"])
    }
}

// ---------------------------------------------------------------------------
// Compliance gates: the properties COMPLIANCE.md claims, asserted by the build.
// ---------------------------------------------------------------------------
tasks.register("complianceCheck") {
    group = "verification"
    description = "Zero runtime dependencies in lck-spi and lck; no telemetry anywhere"

    val deps = listOf("lck-spi", "lck").associateWith { n ->
        project(":$n").configurations.named("runtimeClasspath")
            .map { c -> c.allDependencies.map { "${it.group}:${it.name}" } }
    }
    val sources = fileTree(rootDir) { include("*/src/main/java/**/*.java") }

    doLast {
        deps.forEach { (name, p) ->
            val external = p.get().filterNot { it.startsWith("com.technomorph") }
            require(external.isEmpty()) { "$name must have zero external runtime deps, found: $external" }
        }
        val banned = Regex("""(?i)(analytics|telemetry|posthog|segment\.io|sentry|mixpanel)""")
        sources.forEach { f ->
            banned.find(f.readText())?.let {
                throw GradleException("telemetry-shaped reference '${it.value}' in ${f.relativeTo(rootDir)}")
            }
        }
        logger.lifecycle("compliance: zero runtime dependencies, no telemetry, reproducible jars")
    }
}

tasks.register("verify") {
    group = "verification"
    description = "Everything CI runs"
    dependsOn(subprojects.map { "${it.path}:check" }, "complianceCheck")
}
