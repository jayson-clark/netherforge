package dev.netherforge.plugin.api

import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.NfPacksApi
import dev.netherforge.plugin.session.ProjectSession

/** `nf.packs`: the project's packs read again from disk, and which are `onDemand`. */
internal class NfPacksImpl(private val session: ProjectSession) : NfPacksApi {
    override fun rebuild(caller: Caller) {
        session.rebuildResourcePacks()
    }

    override fun onDemand(caller: Caller): List<String> = session.packs.onDemand.keys.sorted().map(session.names::spell)
}
