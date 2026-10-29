package dev.netherforge.plugin.api
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NfEconomyApi
import dev.netherforge.plugin.binding.NfPlaceholdersApi
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.platform.EconomyOps
import dev.netherforge.plugin.session.ProjectSession

/**
 * `nf.economy`: Vault's economy in plain numbers. Each call has already been
 * checked to be declared (`requires: "plugin:vault"`); it then needs Vault
 * enabled, and an economy plugin registered with it, as of this call.
 */
internal class NfEconomyImpl(private val session: ProjectSession) : NfEconomyApi {
    override fun balance(caller: Caller, player: LuaHandle.Player): Double {
        val economy = economy("nf.economy.balance")
        return player.uuidOrNull()?.let(economy::balance) ?: 0.0
    }

    override fun deposit(caller: Caller, player: LuaHandle.Player, amount: Double): Double? {
        amount("nf.economy.deposit", amount)
        val economy = economy("nf.economy.deposit")
        return player.uuidOrNull()?.let { economy.deposit(it, amount) }
    }

    override fun withdraw(caller: Caller, player: LuaHandle.Player, amount: Double): Double? {
        amount("nf.economy.withdraw", amount)
        val economy = economy("nf.economy.withdraw")
        return player.uuidOrNull()?.let { economy.withdraw(it, amount) }
    }

    private fun amount(call: String, amount: Double) {
        if (!amount.isFinite() || amount <= 0.0) {
            throw LuaApiException("bad argument 'amount' ($call takes an amount above 0 that isn't infinite or NaN, not $amount)")
        }
    }

    private fun economy(call: String): EconomyOps {
        session.plugins.require("vault", call)
        return session.platform.economy()
            ?: throw LuaApiException(
                "$call needs an economy: Vault is enabled, but no economy plugin has registered with it (EssentialsX, for one, does)"
            )
    }
}

/** `nf.placeholders`: PlaceholderAPI's placeholders, filled in and offered. */
internal class NfPlaceholdersImpl(private val session: ProjectSession) : NfPlaceholdersApi {
    override fun parse(caller: Caller, text: String, player: LuaHandle.Player?): String {
        session.plugins.require(PLUGIN, "nf.placeholders.parse")
        return session.platform.placeholders.parse(player?.uuidOrNull(), text)
    }

    override fun register(caller: Caller, namespace: String, callback: LuaFunction) {
        try {
            session.plugins.require(PLUGIN, "nf.placeholders.register")
        } catch (e: LuaApiException) {
            session.scripts.unref(callback.ref)
            throw e
        }
        session.placeholders.register(caller.scope, namespace, callback, own = session.names.calling())
    }

    private companion object {
        const val PLUGIN = "placeholderapi"
    }
}
