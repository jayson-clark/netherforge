import org.gradle.api.provider.Property

/**
 * `paperAdapter { }` in an adapter's build file (`apps/plugin/paper-<minecraft>`):
 * which Paper build it's for. The Minecraft version itself is the folder's name.
 */
abstract class PaperAdapterExtension {
    /**
     * The Paper build the adapter compiles against (paper-api and its dev
     * bundle) and the integration test runs: `26.3.build.181-stable`. Paper
     * published 1.21.x as snapshots instead (`1.21.11-R0.1-SNAPSHOT`); those
     * need [serverBuild] too.
     */
    abstract val paper: Property<String>

    /**
     * The server build to run (Paper's downloads API): read from [paper]
     * (`…build.181-stable` is 181) unless it's a snapshot.
     */
    abstract val serverBuild: Property<Int>

    /**
     * The adapter whose `src/` (what its server says its own way) this one
     * compiles with the shared `paper-internals`, by Minecraft version: its
     * own by default. A version whose server says it all as another's did
     * names that one, and is then nothing but its build file: still compiled
     * against its own server (see the minecraft-versions skill).
     */
    abstract val sources: Property<String>
}
