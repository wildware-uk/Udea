plugins {
    id("udea.kotlin-library")
    // The Replicator contract ships an executable specification: TransformReplicator and
    // ArrayFieldStore. udea-codegen's golden tests consume them, so they have to be a
    // published variant rather than this module's private test source (issue #28 scope).
    `java-test-fixtures`
}

dependencies {
    api(project(":udea-annotations"))

    // `api`, not `implementation`: SimSystem extends Fleks' IntervalSystem and NetIdIndex
    // resolves to a Fleks Entity, so both are part of udea-core's public surface. Fleks is
    // headless — this does not put GL on anyone's classpath (spec 4).
    api(libs.fleks)

    // ReplicatorApiShapeTest asserts the frozen signature exposes FieldMask and never a raw
    // Long. JVM erasure hides a value class, so the check has to run on Kotlin's reflection.
    testImplementation(kotlin("reflect"))
}
