package dev.netherforge.plugin.store

import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Connection
import java.sql.PreparedStatement

/**
 * What `nf.db`'s handle runs statements on: a package's own SQLite file
 * ([Database]) or a named connection to a MySQL or PostgreSQL server
 * ([RemoteDatabase]). Everything a script sees (the three calls, `?`
 * binding, rows as tables, `value, err`) is one implementation over this;
 * what differs between the servers is only here.
 */
interface SqlDatabase {
    /**
     * Runs [work] on a connection, **on the lane the work was given to**
     * (the workers' lane for this database), and returns what it returns.
     * What it throws is the database's answer: an [java.sql.SQLException].
     */
    fun <T> onLane(work: (Connection) -> T): T

    /** [sql] prepared on [connection]; [forWrite] when the statement is `db:execute`'s, which may ask for the key it generates. */
    fun prepare(connection: Connection, sql: String, forWrite: Boolean): PreparedStatement

    /** The key [statement] (prepared from [sql] by [prepare] for a write) just generated, or null: what `db:execute` calls `last_insert_id`. */
    fun lastInsertId(connection: Connection, statement: PreparedStatement, sql: String): Long?
}

/**
 * A column's value as a script reads it, the same from every database: an
 * integer, a real, a string, or null. Whatever else a server returns
 * (a boolean, a decimal, a date) becomes one of those, and a blob is the
 * script's mistake, said in the statement's words.
 */
internal object Cells {
    /** [value] as a script's value, or throws [Blob] for bytes. */
    fun lua(value: Any?): Any? = when (value) {
        null -> null
        is Long, is Double, is String -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Float -> value.toDouble()
        // MySQL's TINYINT(1) and PostgreSQL's boolean arrive as a boolean; SQLite has none, and a script wrote it as 1 or 0.
        is Boolean -> if (value) 1L else 0L
        is BigInteger -> if (value.bitLength() < Long.SIZE_BITS) value.toLong() else value.toString()
        is BigDecimal -> value.toDouble()
        is ByteArray -> throw Blob()
        else -> value.toString()
    }

    /** A column that holds bytes. */
    class Blob : Exception()
}
