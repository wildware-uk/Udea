package dev.wildware.udea

import org.reflections.Reflections
import org.reflections.scanners.Scanners
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import kotlin.reflect.KClass

object UdeaReflections {
    private val registeredProjects = mutableListOf<KClass<*>>()
    lateinit var udeaReflections: Reflections

    init {
        registerProject(UdeaReflections::class)
    }

    fun registerProject(kClass: KClass<*>) {
        registeredProjects += kClass
    }

    fun init() {
        val urls = registeredProjects
            .map { ClasspathHelper.forClass(it.java) }

        udeaReflections = Reflections(
            ConfigurationBuilder()
                .setUrls(urls)
                .setScanners(
                    Scanners.SubTypes, Scanners.TypesAnnotated
                )
        )
    }
}
