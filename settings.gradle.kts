rootProject.name = "ledger-conformance-kit"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// Three modules. Each exists because it has a different DEPENDENCY PROFILE — that is the
// only good reason to split a Gradle project, and the reason each one is here:
//
//   lck-spi     zero deps, Java 17 bytecode. This is what CLIENTS compile against, so it
//               must be small enough to review and old enough to consume.
//   lck         zero deps, Java 21. The engine. Separate from the SPI so a client's
//               adapter cannot accidentally depend on our internals.
//   lck-junit5  the only module that pulls a third-party jar (junit-jupiter-api).
//               Separate so nobody using the CLI drags JUnit onto their classpath.
//
// Everything else — invariants, TCK, reports, examples, CLI, HTTP adapter — is a PACKAGE
// inside lck, not a module. Packages are free; modules cost a build file, a POM, a
// version, and a line in every consumer's dependency review.
include("lck-spi", "lck", "lck-junit5")
