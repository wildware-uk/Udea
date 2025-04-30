package dev.wildware.udea

import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.messages.MessageBusConnection
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Paths

/**
 * Service that manages a project classloader that automatically updates when the project is built.
 * This replaces the previous approach of using PSI to find classes.
 */
@Service(Service.Level.PROJECT)
class ProjectClassLoaderManager(private val project: Project) {

    // The current classloader for the project
    var classLoader: ClassLoader = javaClass.classLoader
        private set

    // Message bus connection for listening to compiler events
    private val messageBusConnection: MessageBusConnection = project.messageBus.connect()

    init {
        // Initialize the classloader
        updateClassLoader()

        // Register a listener for build events using the message bus
        messageBusConnection.subscribe(CompilerTopics.COMPILATION_STATUS, object : CompilationStatusListener {
            override fun compilationFinished(aborted: Boolean, errors: Int, warnings: Int, context: CompileContext) {
                if (!aborted && errors == 0) {
                    // Update the classloader when compilation succeeds
                    updateClassLoader()
                }
            }
        })
    }

    /**
     * Updates the classloader to include the latest compiled classes
     */
    private fun updateClassLoader() {
        try {
            // Find the core module
            val module = ModuleManager.getInstance(project)
                .modules.find { "core" in it.name }

            if (module != null) {
                val projectBasePath = module.rootManager.contentRoots.firstOrNull()?.path

                if (projectBasePath != null) {
                    val buildDir = Paths.get(projectBasePath, "build", "classes", "kotlin", "main")
                        .toUri().toURL()

                    val allUrls = arrayOf(buildDir) + getGradleDependencies(project).toTypedArray()
                    // Create a URLClassLoader with the output directories
                    classLoader = URLClassLoader(allUrls, javaClass.classLoader)
                    println("ProjectClassLoaderManager: Classloader updated")
                }
            }
        } catch (e: Exception) {
            println("ProjectClassLoaderManager: Error updating classloader: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Gets the Gradle dependencies of the project as URLs
     * 
     * @param project The project to get dependencies for
     * @return A list of URLs representing the Gradle dependencies
     */
    private fun getGradleDependencies(project: Project): List<URL> {
        val urls = mutableListOf<URL>()

        try {
            // Get the project library table
            val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

            // Add all libraries from the project library table
            projectLibraryTable.libraries.forEach { library ->
                library.getFiles(OrderRootType.CLASSES).forEach { virtualFile ->
                    try {
                        val url = VfsUtilCore.virtualToIoFile(virtualFile).toURI().toURL()
                        urls.add(url)
                    } catch (e: Exception) {
                        // Try an alternative approach if toNioPath fails
                        try {
                            val url = URL(virtualFile.url)
                            urls.add(url)
                        } catch (e2: Exception) {
                            println("ProjectClassLoaderManager: Error converting virtual file to URL: ${e2.message}")
                        }
                    }
                }
            }

            // Get the application library table (global libraries)
            val globalLibraryTable = LibraryTablesRegistrar.getInstance().libraryTable

            // Add all libraries from the global library table
            globalLibraryTable.libraries.forEach { library ->
                library.getFiles(OrderRootType.CLASSES).forEach { virtualFile ->
                    try {
                        val url = virtualFile.toNioPath().toUri().toURL()
                        urls.add(url)
                    } catch (e: Exception) {
                        // Try an alternative approach if toNioPath fails
                        try {
                            val url = URL(virtualFile.url)
                            urls.add(url)
                        } catch (e2: Exception) {
                            println("ProjectClassLoaderManager: Error converting virtual file to URL: ${e2.message}")
                        }
                    }
                }
            }

            println("ProjectClassLoaderManager: Found ${urls.size} Gradle dependencies")
        } catch (e: Exception) {
            println("ProjectClassLoaderManager: Error getting Gradle dependencies: ${e.message}")
            e.printStackTrace()
        }

        return urls
    }

    /**
     * Disposes the service, cleaning up resources
     */
    fun dispose() {
        messageBusConnection.disconnect()
    }

    companion object {
        /**
         * Gets the ProjectClassLoaderManager instance for the given project
         */
        fun getInstance(project: Project): ProjectClassLoaderManager {
            return project.service<ProjectClassLoaderManager>()
        }
    }
}
