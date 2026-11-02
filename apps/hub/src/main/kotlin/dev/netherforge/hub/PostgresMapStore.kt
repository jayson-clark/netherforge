package dev.netherforge.hub

import dev.netherforge.network.MapStore
import dev.netherforge.network.Protocol
import dev.netherforge.network.SavedMaps
import java.sql.Connection
import java.sql.DriverManager

/**
 * The network's shared maps in a PostgreSQL database, so a hub running on its own can restart or move without losing
 * them: one row of one table (`netherforge_hub_maps`), the maps as JSON, written whole each save as the file store
 * writes its file. The table is made the first time.
 */
class PostgresMapStore(private val url: String, private val user: String?, private val password: String?) : MapStore {
    private fun connect(): Connection = DriverManager.getConnection(url, user, password).also { connection ->
        connection.createStatement().use {
            it.execute(
                "CREATE TABLE IF NOT EXISTS netherforge_hub_maps (id SMALLINT PRIMARY KEY, maps TEXT NOT NULL, saved_at TIMESTAMPTZ NOT NULL)"
            )
        }
    }

    override fun load(): SavedMaps? = connect().use { connection ->
        connection.prepareStatement("SELECT maps FROM netherforge_hub_maps WHERE id = 1").use { statement ->
            statement.executeQuery().use { rows ->
                if (rows.next()) Protocol.json.decodeFromString(SavedMaps.serializer(), rows.getString(1)) else null
            }
        }
    }

    override fun save(maps: SavedMaps) {
        val text = Protocol.json.encodeToString(SavedMaps.serializer(), maps)
        connect().use { connection ->
            connection.prepareStatement(
                "INSERT INTO netherforge_hub_maps (id, maps, saved_at) VALUES (1, ?, now()) " +
                    "ON CONFLICT (id) DO UPDATE SET maps = EXCLUDED.maps, saved_at = EXCLUDED.saved_at"
            ).use { statement ->
                statement.setString(1, text)
                statement.executeUpdate()
            }
        }
    }
}
