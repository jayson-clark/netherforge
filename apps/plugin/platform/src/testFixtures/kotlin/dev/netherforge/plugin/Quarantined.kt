package dev.netherforge.plugin

import org.junit.jupiter.api.Tag
import org.junit.platform.commons.support.ReflectionSupport
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method

/**
 * A flaky test taken out of the normal run until it's fixed: JUnit's
 * `quarantine` tag, which every `test` task in the plugin's builds excludes
 * (`quarantinedTest` runs only these). [issue] is the link to the issue that
 * tracks the fix, required so nothing is quarantined and forgotten;
 * `QuarantineTest` holds every use to a real issue URL.
 *
 * Put it on the test method (or a whole class) the day it's seen flaking, and
 * take it off in the change that fixes it. Never retry a flaky test instead.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag(Quarantined.TAG)
annotation class Quarantined(val issue: String) {
    companion object {
        /** The JUnit tag, shared with the integration tests and CI. */
        const val TAG = "quarantine"

        /** What [issue] must be: an issue in the project's GitHub repository. */
        val ISSUE = Regex("""https://github\.com/[\w.-]+/[\w.-]+/issues/\d+""")

        /** [problems] in [packageName]'s classes on the test classpath, but [except]. */
        fun problems(packageName: String, except: (Class<*>) -> Boolean = { false }): List<String> =
            problems(ReflectionSupport.findAllClassesInPackage(packageName, { !it.isAnnotation && !except(it) }, { true }))

        /** What's wrong with the quarantined tests in [classes]: an [issue] that isn't an issue's URL, or the tag put on by hand. */
        fun problems(classes: List<Class<*>>): List<String> {
            val elements = classes.flatMap { listOf<AnnotatedElement>(it) + it.declaredMethods }
            return elements.flatMap { element ->
                val name = (element as? Method)?.let { "${it.declaringClass.name}.${it.name}" } ?: (element as Class<*>).name
                val quarantined = element.getAnnotation(Quarantined::class.java)
                val tagged = element.getAnnotationsByType(Tag::class.java).any { it.value == TAG }
                listOfNotNull(
                    quarantined?.takeUnless { ISSUE.matches(it.issue) }?.let { "$name: \"${it.issue}\" isn't an issue's URL" },
                    "$name: tagged \"$TAG\" without @Quarantined(issue)".takeIf { tagged && quarantined == null }
                )
            }
        }
    }
}
