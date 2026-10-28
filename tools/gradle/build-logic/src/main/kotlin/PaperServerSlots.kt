import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * How many Paper servers the build runs at once: tasks that boot one declare
 * `usesService(…)` on this, registered with `maxParallelUsages` (see the
 * integration project's build file). It holds nothing; Gradle's limit on its
 * concurrent users is the point.
 */
abstract class PaperServerSlots : BuildService<BuildServiceParameters.None>
