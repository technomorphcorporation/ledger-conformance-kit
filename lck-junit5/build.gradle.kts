// The only module with a third-party compile dependency. Kept separate so that anyone
// using the CLI or the library does not drag JUnit onto their classpath.
dependencies {
    api(project(":lck"))
    api(libs.junit.jupiter.api)        // versioned in libs.versions.toml, not by the BOM
}
