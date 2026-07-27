package korlibs.korge.gradle.util

import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.api.tasks.*
import kotlin.reflect.*

fun <T> ExtensionContainer.getByName(name: String): T = getByName(name) as T

inline fun <reified T : Task> TaskContainer.createThis(name: String, vararg params: Any, block: T.() -> Unit = {}): T {
    return create(name, T::class.java, *params).apply(block)
}

/**
 * Lazy counterpart to [createThis]. Uses `register` instead of `create` so the task instance
 * (and any base-class property finalization, e.g. JavaExec's javaLauncher) is not constructed
 * until Gradle actually needs it, avoiding ordering conflicts with core plugins' task-added
 * listeners (e.g. JavaBasePlugin.configureJavaExecTasks) on newer Gradle versions.
 */
inline fun <reified T : Task> TaskContainer.registerThis(name: String, vararg params: Any, noinline block: T.() -> Unit = {}): TaskProvider<T> {
    return register(name, T::class.java, *params).apply { configure(block) }
}

fun Project.`kover`(configure: Action<KoverProjectExtension>): Unit = (this as ExtensionAware).extensions.configure("kover", configure)

/**
 * Retrieves the [ext][ExtraPropertiesExtension] extension.
 */
val Project._ext: ExtraPropertiesExtension get() =
    (this as ExtensionAware).extensions.getByName("ext") as ExtraPropertiesExtension

class LazyExt<T>(val root: Boolean = true, val lazyBlock: Project.() -> T) {
    operator fun getValue(project: Project, property: KProperty<*>): T {
        val key = "_ext_${property.name}"
        if (!project._ext.has(key)) {
            project._ext.set(key, lazyBlock(if (root) project.rootProject else project))
        }
        return project._ext.get(key) as T
    }
}
