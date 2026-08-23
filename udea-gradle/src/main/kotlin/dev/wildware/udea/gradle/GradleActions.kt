package dev.wildware.udea.gradle

/**
 * An [org.gradle.api.Action] from a plain lambda, written out rather than SAM-converted.
 *
 * Every file in this module is compiled **twice** - once as `:udea-gradle`, and once inside
 * `build-logic` so that this build can apply these plugins to `:moba` (see
 * `build-logic/build.gradle.kts`). The second compilation has the Gradle Kotlin DSL on its
 * classpath, and the DSL declares its own `fun <T> Action(configuration: T.() -> Unit)`, which
 * shadows the SAM constructor and takes a *receiver* lambda. `configure { task -> ... }`
 * therefore means two different things in the two compilations and fails to compile in one of
 * them - with `Variable expected` on the first assignment inside the block, which names nothing
 * about the real cause.
 *
 * An explicit anonymous object means the same thing in both, which is the property this module
 * needs above brevity. It lives in a file of its own because two plugins now need it, and a
 * `private` copy in each is the same mistake at a smaller scale.
 */
internal fun <T : Any> gradleAction(block: (T) -> Unit): org.gradle.api.Action<T> =
    object : org.gradle.api.Action<T> {
        override fun execute(target: T) {
            block(target)
        }
    }

/** An [org.gradle.api.specs.Spec] from a predicate, written out for the reason [gradleAction] is. */
internal fun <T : Any> gradleSpec(predicate: (T) -> Boolean): org.gradle.api.specs.Spec<T> =
    object : org.gradle.api.specs.Spec<T> {
        override fun isSatisfiedBy(element: T): Boolean = predicate(element)
    }
