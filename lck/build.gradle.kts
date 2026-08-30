plugins { application }

dependencies {
    api(project(":lck-spi"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
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
