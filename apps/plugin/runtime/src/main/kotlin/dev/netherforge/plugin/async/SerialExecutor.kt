package dev.netherforge.plugin.async

import java.util.concurrent.Executor

/**
 * Runs what it's given on [over] one piece at a time, in the order it was given: a worker lane (a world's files
 * copied, then deleted; the store's batches), or one WebAssembly instance's calls. The next piece is handed to
 * [over] before the one before it finishes, so a pool waited on until idle never looks idle between them.
 */
class SerialExecutor(private val over: Executor) : Executor {
    private val queue = ArrayDeque<Runnable>()
    private var active: Runnable? = null

    @Synchronized
    override fun execute(command: Runnable) {
        queue.addLast(
            Runnable {
                try {
                    command.run()
                } finally {
                    next()
                }
            }
        )
        if (active == null) next()
    }

    @Synchronized
    private fun next() {
        active = queue.removeFirstOrNull()
        active?.let(over::execute)
    }
}
