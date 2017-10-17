package org.jetbrains.kotlin.gradle.plugin

import groovy.lang.Closure
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.HasConvention
import org.gradle.api.internal.file.DefaultSourceDirectorySet
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.util.ConfigureUtil
import java.lang.reflect.Constructor
import javax.inject.Inject


//TODO reuse KotlinPlatformImplementationPluginBase
open class KotlinPlatformNativePlugin @Inject constructor(val fileResolver: FileResolver) : NativePlatformImplementationPluginBase("native") {
    override fun apply(project: Project) {
        val javaBasePlugin = project.plugins.apply(JavaBasePlugin::class.java)
        val javaPluginConvention = project.convention.getPlugin(JavaPluginConvention::class.java)

        project.plugins.apply(JavaPlugin::class.java)

        project.applyPlugin<KonanPlugin>()
        super.apply(project)

        configureSourceSetDefaults(project, javaPluginConvention, fileResolver)
    }


    open protected fun configureSourceSetDefaults(
            project: Project,
            javaPluginConvention: JavaPluginConvention,
            fileResolver: FileResolver
    ) {
        javaPluginConvention.sourceSets?.all { sourceSet ->
            val kotlinSourceSet = KotlinSourceSetImpl(sourceSet.name, fileResolver)
            kotlinSourceSet.kotlin.srcDir(project.file("src/${sourceSet.name}/kotlin"))
            sourceSet.addConvention("kotlin", kotlinSourceSet)

            sourceSet.allSource.source(kotlinSourceSet.kotlin)
        }
    }
}

internal inline fun <reified T : Any> Any.addConvention(name: String, plugin: T) {
    (this as HasConvention).convention.plugins[name] = plugin
}


// Patched copy of KotlinPlatformImplementationPluginBase from kotlin-gradle-plugin
// TODO switch to origin
open class NativePlatformImplementationPluginBase(platformName: String) : KotlinPlatformPluginBase(platformName) {
    private val commonProjects = arrayListOf<Project>()
    private val platformKotlinTasksBySourceSetName = hashMapOf<String, KonanCompileTask>()

    override fun apply(project: Project) {
        project.tasks.withType(KonanCompileTask::class.java).all {
            (it as KonanCompileTask).multiPlatform = true
        }

        //project.tasks.filterIsInstance<KonanCompileTask>().associateByTo(platformKotlinTasksBySourceSetName) { it.sourceSetName }

        val implementConfig = project.configurations.create("implement")
        implementConfig.isTransitive = false
        implementConfig.dependencies.whenObjectAdded { dep ->
            if (dep is ProjectDependency) {
                addCommonProject(dep.dependencyProject, project)
            }
            else {
                throw GradleException("$project `implement` dependency is not a project: $dep")
            }
        }
    }

    private fun addCommonProject(commonProject: Project, platformProject: Project) {
        commonProjects.add(commonProject)
        if (commonProjects.size > 1) {
            throw GradleException("Platform project $platformProject implements more than one common project: ${commonProjects.joinToString()}")
        }

        commonProject.whenEvaluated {
            if ((!commonProject.plugins.hasPlugin(KotlinPlatformCommonPlugin::class.java))) {
                throw GradleException("Platform project $platformProject implements non-common project $commonProject (`apply plugin 'kotlin-platform-kotlin'`)")
            }

            commonProject.sourceSets.all { commonSourceSet ->
                //assume that common part always lies in main and native one in native
                commonSourceSet.kotlin!!.let {
                    sourceDirectorySet: SourceDirectorySet ->
                    println("add commonSourceSet $sourceDirectorySet")
                    platformProject.tasks.withType(KonanCompileTask::class.java).all {
                        it.source(sourceDirectorySet)
                    }
                }
            }
        }
    }

    private val SourceSet.kotlin: SourceDirectorySet?
        get() = ((getConvention("kotlin") ?: getConvention("kotlin2js")) as? KotlinSourceSet)?.kotlin
}

//TODO switch to public one in kotlin plugin
private inline fun <reified T : Plugin<*>> Project.applyPlugin() {
    pluginManager.apply(T::class.java)
}

//TODO switch to public one in kotlin plugin
private fun <T> Project.whenEvaluated(fn: Project.()->T) {
    if (state.executed) {
        fn()
    }
    else {
        afterEvaluate { it.fn() }
    }
}

//TODO switch to public one in kotlin plugin
internal fun Any.getConvention(name: String): Any? =
        (this as HasConvention).convention.plugins[name]

//TODO switch to public one in kotlin plugin
private val Project.sourceSets: SourceSetContainer
    get() = convention.getPlugin(JavaPluginConvention::class.java).sourceSets


private class KotlinSourceSetImpl(displayName: String, resolver: FileResolver) : KotlinSourceSet {
    override val kotlin: SourceDirectorySet =
            createDefaultSourceDirectorySet(displayName + " Kotlin source", resolver)

    init {
        kotlin.filter?.include("**/*.java", "**/*.kt", "**/*.kts")
    }

    override fun kotlin(configureClosure: Closure<Any?>?): KotlinSourceSet {
        ConfigureUtil.configure(configureClosure, kotlin)
        return this
    }
}

private val createDefaultSourceDirectorySet: (name: String?, resolver: FileResolver?) -> SourceDirectorySet = run {
    val klass = DefaultSourceDirectorySet::class.java

    val directoryFileTreeFactoryClass = Class.forName("org.gradle.api.internal.file.collections.DirectoryFileTreeFactory")
    val alternativeConstructor = klass.getConstructor(String::class.java, FileResolver::class.java, directoryFileTreeFactoryClass)

    val defaultFileTreeFactoryClass = Class.forName("org.gradle.api.internal.file.collections.DefaultDirectoryFileTreeFactory")
    val defaultFileTreeFactory = defaultFileTreeFactoryClass.getConstructor().newInstance()
    return@run { name, resolver -> alternativeConstructor.newInstance(name, resolver, defaultFileTreeFactory) }

}