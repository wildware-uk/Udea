plugins {
    id("udea.kotlin-library")
}

dependencies {
    api(project(":udea-core"))
}

/**
 * The golden hex fixture pins the bit layout, so it has to be regenerable on purpose and
 * never by accident. `./gradlew :udea-net:test -Dupdate.goldens=true` rewrites it; without
 * the flag a layout change is a failing diff.
 *
 * Gradle has no `--update-goldens` CLI option for a plain `Test` task, so the flag is a
 * system property. `udea.projectDir` gives the test the source path to rewrite, which the
 * classpath alone cannot provide.
 */
val updateGoldens: Provider<String> = providers.systemProperty("update.goldens").orElse("false")

tasks.withType<Test>().configureEach {
    systemProperty("udea.projectDir", projectDir.absolutePath)
    systemProperty("update.goldens", updateGoldens.get())
}
