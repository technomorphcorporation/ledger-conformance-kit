plugins {
    `java-library`
    `maven-publish`
    signing
}

allprojects {
    // Maven Central verifies that a group id belongs to you. io.github.<org> is verified by
    // ownership of the GitHub organisation, which avoids requiring a domain nobody owns —
    // com.technomorphcorporation would have needed technomorphcorporation.com.
    group = "io.github.technomorphcorporation"
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
        withJavadocJar()      // Maven Central rejects a release without one
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

    tasks.withType<Javadoc>().configureEach {
        // Check that the javadoc we wrote is correct — broken @link targets, malformed HTML —
        // without demanding the javadoc we deliberately did not write. "-missing" drops the
        // "no @param for accountId" class of warning: a @param that restates the parameter name
        // is the documentation equivalent of a comment that restates the code.
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            addStringOption("Xdoclint:all,-missing", "-quiet")
        }
        // No external -links: fetching element-list from docs.oracle.com would make the build
        // depend on the network, and this one is meant to work air-gapped.
    }

    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false      // reproducible builds
        isReproducibleFileOrder = true
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                // Matches the root package of each module — com.technomorph.lck.spi, com.technomorph.lck,
                // com.technomorph.lck.junit5 — rather than the publishing coordinates, so a module
                // name and the packages inside it agree.
                "Automatic-Module-Name" to "com.technomorph." + project.name.replace('-', '.')
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
                    // Central validates the scm block; connection and developerConnection are
                    // part of what it checks, not decoration.
                    scm {
                        url.set("https://github.com/technomorphcorporation/ledger-conformance-kit")
                        connection.set("scm:git:https://github.com/technomorphcorporation/ledger-conformance-kit.git")
                        developerConnection.set("scm:git:ssh://git@github.com/technomorphcorporation/ledger-conformance-kit.git")
                    }
                }
            }
        }
    }

    configure<SigningExtension> {
        // Required exactly when a release bundle is being built. The previous condition looked
        // for a task literally named "publish", which the bundle path never creates — so a
        // release would have produced unsigned artifacts and been rejected at the far end.
        setRequired({
            !version.toString().endsWith("SNAPSHOT") && gradle.taskGraph.hasTask(":centralBundle")
        })
        providers.gradleProperty("signingKey").orNull?.let {
            useInMemoryPgpKeys(it, providers.gradleProperty("signingPassword").orNull)
        }
        sign(extensions.getByType<PublishingExtension>().publications["maven"])
    }

    // A file repository, not a remote one: the Central Publisher Portal takes a single zip
    // rather than accepting individual deploys, so the "upload" is assembling a directory.
    configure<PublishingExtension> {
        repositories {
            maven {
                name = "centralBundle"
                url = uri(rootProject.layout.buildDirectory.dir("central-bundle"))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Compliance gates: the properties COMPLIANCE.md claims, asserted by the build.
// ---------------------------------------------------------------------------
tasks.register("complianceCheck") {
    group = "verification"
    description = "Zero runtime dependencies in lck-spi and lck; no telemetry anywhere"

    // Internal modules are identified by being project dependencies, not by a prefix on the
    // group id. The prefix version silently depended on the publishing coordinates matching the
    // package names, and broke the moment the group moved to io.github.technomorphcorporation —
    // a gate that reads a name to decide what is ours is a gate that fails on a rename.
    val deps = listOf("lck-spi", "lck").associateWith { n ->
        project(":$n").configurations.named("runtimeClasspath")
            .map { c ->
                c.allDependencies
                    .filterNot { it is ProjectDependency }
                    .map { "${it.group}:${it.name}" }
            }
    }
    val sources = fileTree(rootDir) { include("*/src/main/java/**/*.java") }

    doLast {
        deps.forEach { (name, external) ->
            require(external.get().isEmpty()) {
                "$name must have zero external runtime deps, found: ${external.get()}"
            }
        }
        val banned = Regex("""(?i)(analytics|telemetry|posthog|segment\.io|sentry|mixpanel)""")

        // A report is opened on a reviewer's machine inside a bank. Any asset it references
        // over the network is an outbound request COMPLIANCE.md says we never make — and a
        // webfont link sat in the report template until this check was written, because the
        // banned-word list above cannot see a URL. Match the shape of a fetched asset rather
        // than a hostname: a blocklist of vendors only catches the vendors already thought of.
        val remoteAsset = Regex("""(?i)(?:href|src)\s*=\s*\\?["']https?://|@import\s+(?:url\()?\s*\\?["']?https?://""")

        sources.forEach { f ->
            val text = f.readText()
            banned.find(text)?.let {
                throw GradleException("telemetry-shaped reference '${it.value}' in ${f.relativeTo(rootDir)}")
            }
            remoteAsset.find(text)?.let {
                throw GradleException("emitted output fetches a remote asset ('${it.value.trim()}...') in "
                        + "${f.relativeTo(rootDir)} — reports must render with no network access")
            }
        }
        logger.lifecycle("compliance: zero runtime dependencies, no telemetry, "
                + "no remote assets in emitted output, reproducible jars")
    }
}

// ---------------------------------------------------------------------------
// Release bundle for the Central Publisher Portal.
//
// Deliberately no third-party Gradle plugin. This project asks a reviewer to accept that its
// artifacts carry no dependencies; adding a publishing plugin with its own transitive tree to
// the build that produces them would be an odd place to stop caring. What the Portal wants is
// a zip of a Maven repository directory, which maven-publish already knows how to write.
// ---------------------------------------------------------------------------
tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Signed, checksummed bundle for upload to central.sonatype.com"

    dependsOn(subprojects.map { "${it.path}:publishMavenPublicationToCentralBundleRepository" })

    from(layout.buildDirectory.dir("central-bundle")) {
        // The Portal rejects a bundle containing maven-metadata; it maintains that itself.
        exclude("**/maven-metadata.xml*")
    }
    archiveFileName.set("central-bundle-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    doFirst {
        check(!project.version.toString().endsWith("SNAPSHOT")) {
            "refusing to bundle ${project.version}: Central takes releases, not snapshots. " +
            "Pass -Pversion=1.0.0"
        }
        delete(layout.buildDirectory.dir("central-bundle"))   // never ship a stale artifact
    }
}

tasks.register("verify") {
    group = "verification"
    description = "Everything CI runs"
    dependsOn(subprojects.map { "${it.path}:check" }, "complianceCheck")
}
