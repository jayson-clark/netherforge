import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * A test JVM's `-D<name>=<path>` for a file only known once the build runs
 * (a jar built or downloaded by another task): `jvmArgumentProviders.add(…)`
 * on a `Test`. The file is the task's input, and the path is read at
 * execution, which a `doFirst { systemProperty(…) }` would do by capturing
 * the project's configurations, which the configuration cache can't store.
 */
class SystemPropertyPath(
    @get:Input val name: String,
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val file: FileCollection
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-D$name=${file.singleFile.absolutePath}")
}
