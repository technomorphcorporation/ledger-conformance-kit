import java.util.concurrent.atomic.AtomicInteger

plugins { application }

dependencies {
    api(project(":lck-spi"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)

    // The worked Postgres example. Test-only: the published jars carry no dependencies, and
    // complianceCheck fails the build if that stops being true.
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.postgresql.driver)
}

application {
    mainClass.set("com.technomorph.lck.cli.Main")
    applicationName = "lck"
}

tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Run the suite against the bundled correct and incorrect ledgers"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.technomorph.lck.cli.Main")
    args("demo", "--seed", providers.gradleProperty("lck.seed").getOrElse("42"))
    isIgnoreExitValue = true      // the naive ledger is SUPPOSED to fail
}

// ---------------------------------------------------------------------------
// The Postgres example needs Docker and takes minutes; `verify` runs on every push and
// pull request and should stay in seconds. So it is tagged and split out — but split out
// is one edit away from never running at all, which is the failure mode that matters:
// a suite nobody notices has stopped is worse than one that is slow.
//
// Two things guard against that. failOnNoMatchingTests means a mis-typed tag fails the
// task rather than passing with nothing run, and the workflow asserts afterwards that
// tests actually executed rather than trusting a green tick.
// ---------------------------------------------------------------------------
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("docker") }
}

val dockerTestsSelected = AtomicInteger()

tasks.register<Test>("dockerTest") {
    group = "verification"
    description = "The worked Postgres example. Needs Docker; skips cleanly without it."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("docker") }

    // Counted, not asserted through `filter { isFailOnNoMatchingTests }`, which governs
    // Gradle's --tests patterns and not JUnit Platform tag selection: with the tag misspelt
    // that setting passes a build that ran nothing. Verified by misspelling it.
    //
    // Skipped tests count too, and should. Without Docker these are selected and then skipped,
    // which is the intended behaviour; what must never pass silently is a task that selected
    // no tests at all.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) { }
        override fun afterSuite(suite: TestDescriptor, result: TestResult) { }
        override fun beforeTest(descriptor: TestDescriptor) { }
        override fun afterTest(descriptor: TestDescriptor, result: TestResult) {
            dockerTestsSelected.incrementAndGet()
        }
    })

    doLast {
        check(dockerTestsSelected.get() > 0) {
            "dockerTest selected no tests. The @Tag(\"docker\") selection is wrong, and this " +
            "task would otherwise report success having verified nothing."
        }
    }
}
