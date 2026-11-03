package dev.netherforge.hub

import dev.netherforge.network.AdminClient
import dev.netherforge.network.AdminClientsFile
import dev.netherforge.network.AdminScope
import dev.netherforge.network.Auth
import dev.netherforge.network.CredentialEntry
import dev.netherforge.network.CredentialsFile
import dev.netherforge.network.Grants
import dev.netherforge.network.HubLog
import dev.netherforge.network.NetworkNames
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * `java -jar NetherForge-<version>-hub.jar [run]`: the hub, its files in the working folder (or
 * `-Dnetherforge.hub.folder`), until it's stopped. `credentials add|revoke|list` and `admins add|revoke|list` change
 * its files, which a running hub reads again within seconds.
 */
fun main(args: Array<String>) {
    val folder = Path.of(System.getProperty("netherforge.hub.folder") ?: ".").toAbsolutePath().normalize()
    val out = try {
        when (args.firstOrNull()) {
            null, "run" -> {
                run(folder)
                return
            }
            "credentials" -> credentials(folder, args.drop(1))
            "admins" -> admins(folder, args.drop(1))
            else -> USAGE
        }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    println(out)
}

private fun run(folder: Path) {
    val hub = StandaloneHub(folder, ConsoleLog)
    try {
        hub.start()
    } catch (e: Exception) {
        System.err.println("NetherForge hub couldn't start: ${e.message}")
        exitProcess(1)
    }
    val done = java.util.concurrent.CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            ConsoleLog.info("NetherForge hub stopping")
            hub.stop()
            done.countDown()
        }
    )
    done.await()
}

/** What `credentials` does with [args], as `/nfhub credentials` does on a proxy. */
internal fun credentials(folder: Path, args: List<String>): String {
    val file = CredentialsFile(folder.resolve(CredentialsFile.FILE))
    return when (args.firstOrNull()) {
        "list" -> file.read().joinToString("\n") {
            "${it.id}: ${it.credential(emptyMap()).grants.describe()}"
        }.ifEmpty { "No credentials." }
        "add" -> {
            val id = args.getOrNull(1) ?: throw IllegalArgumentException("Usage: credentials add <id> [role] [owns,...]")
            require(NetworkNames.isCredential(id)) { "\"$id\" isn't a credential id: letters, digits, _ . -" }
            val role = args.getOrNull(2) ?: Grants.STANDARD_ROLE
            require(role in Grants.ROLES) { "\"$role\" isn't a role: ${Grants.ROLES.joinToString()}" }
            val owns = args.getOrNull(3)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val entries = file.read()
            require(entries.none { it.id == id }) { "There's a credential \"$id\" already: revoke it first for a new secret." }
            val entry = CredentialEntry(id, Auth.newSecret(), role, owns)
            file.write(entries + entry)
            "Added the credential $id ($role). Its secret, shown only now: ${entry.secret}"
        }
        "revoke" -> {
            val id = args.getOrNull(1) ?: throw IllegalArgumentException("Usage: credentials revoke <id>")
            val entries = file.read()
            require(entries.any { it.id == id }) { "There's no credential \"$id\"." }
            file.write(entries.filter { it.id != id })
            "Revoked $id."
        }
        else -> "Usage: credentials add <id> [role] [owns,...] | revoke <id> | list"
    }
}

/** What `admins` does with [args], as `/nfhub admins` does on a proxy. */
internal fun admins(folder: Path, args: List<String>): String {
    val file = AdminClientsFile(folder.resolve(AdminClientsFile.FILE))
    return when (args.firstOrNull()) {
        "list" -> file.read().joinToString("\n") { "${it.id}: ${it.scopes.joinToString { s -> s.word }}" }.ifEmpty { "No admin clients." }
        "add" -> {
            val id = args.getOrNull(1) ?: throw IllegalArgumentException("Usage: admins add <id> <scope,scope,...|*>")
            require(NetworkNames.isCredential(id)) { "\"$id\" isn't a client id: letters, digits, _ . -" }
            val words = args.drop(2).joinToString(",").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            require(words.isNotEmpty()) { "Say what it may do: ${AdminScope.entries.joinToString { it.word }}, or * (all but credentials)" }
            val scopes = AdminScope.parse(words) { throw IllegalArgumentException("\"$it\" isn't a scope") }!!
            val all = file.read()
            require(all.none { it.id == id }) { "There's an admin client \"$id\" already: revoke it first for a new token." }
            val client = AdminClient(id, Auth.newSecret(), scopes)
            file.write(all + client)
            "Added the admin client $id. Its token, shown only now: ${client.token}"
        }
        "revoke" -> {
            val id = args.getOrNull(1) ?: throw IllegalArgumentException("Usage: admins revoke <id>")
            val all = file.read()
            require(all.any { it.id == id }) { "There's no admin client \"$id\"." }
            file.write(all.filter { it.id != id })
            "Revoked $id."
        }
        else -> "Usage: admins add <id> <scopes|*> | revoke <id> | list"
    }
}

private const val USAGE = "Usage: java -jar NetherForge-<version>-hub.jar [run | credentials ... | admins ...]"

/** The hub's log: the console, timestamped. */
private object ConsoleLog : HubLog {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    override fun info(message: String) = println("[${LocalTime.now().format(time)} INFO] $message")

    override fun warn(message: String) = println("[${LocalTime.now().format(time)} WARN] $message")
}
