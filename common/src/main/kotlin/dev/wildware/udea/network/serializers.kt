package dev.wildware.udea.network

import org.reflections.Reflections
import org.reflections.util.ConfigurationBuilder
import java.net.URL
import kotlin.reflect.KClass

annotation class Networked

fun getAllNetworked(packageName: String, vararg classLoaders: ClassLoader): List<KClass<*>> {
    val reflections = Reflections(
        ConfigurationBuilder()
            .forPackage(packageName)
            .addUrls(URL("file:/C:/Users/shaun/Workspace/SpellCastGame/core/build/classes/kotlin/main/"))
            .addClassLoaders(*classLoaders)
    )

    val annotatedClasses = reflections.getTypesAnnotatedWith(Networked::class.java)
    return annotatedClasses.map { it.kotlin }
}
