package com.dogmsg.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * T-B8 (lado Android): cache local de mensajes en SQLite.
 *
 * Mismo esquema que LocalCache.java del cliente desktop, pero usando el
 * SQLiteOpenHelper nativo de Android (sin drivers externos). Permite abrir una
 * conversacion mostrando los mensajes recientes sin consultar al servidor.
 */
class LocalCache(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    /** Representa una fila de mensaje cacheado. */
    data class CachedMessage(
        var id: Long = 0,
        var peerId: Long = 0,        // usuario o grupo de la conversacion
        var isGroup: Boolean = false,
        var senderId: Long = 0,
        var type: String = "text",   // text | image | file
        var text: String? = null,    // texto en claro (ya descifrado localmente)
        var timestamp: Long = 0,
        var status: String = "sent"  // sent | delivered | read
    )

    /** Resumen de una conversacion: ultimo mensaje, para poblar la lista de chats. */
    data class ConversationSummary(
        val peerId: Long,
        val isGroup: Boolean,
        val lastText: String?,
        val lastType: String,
        val lastTimestamp: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cache_messages (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              server_id INTEGER,
              peer_id INTEGER NOT NULL,
              is_group INTEGER DEFAULT 0,
              sender_id INTEGER NOT NULL,
              type TEXT DEFAULT 'text',
              text TEXT,
              timestamp INTEGER NOT NULL,
              status TEXT DEFAULT 'sent'
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_peer_time " +
                    "ON cache_messages(peer_id, is_group, timestamp)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS cache_messages")
        onCreate(db)
    }

    /** Inserta un mensaje en el cache local y devuelve su id local. */
    @Synchronized
    fun insert(m: CachedMessage): Long {
        val values = ContentValues().apply {
            put("server_id", m.id)
            put("peer_id", m.peerId)
            put("is_group", if (m.isGroup) 1 else 0)
            put("sender_id", m.senderId)
            put("type", m.type)
            put("text", m.text)
            put("timestamp", m.timestamp)
            put("status", m.status)
        }
        return writableDatabase.insert("cache_messages", null, values)
    }

    /** Carga los ultimos [limit] mensajes de una conversacion (orden cronologico). */
    @Synchronized
    fun recent(peerId: Long, isGroup: Boolean, limit: Int): List<CachedMessage> {
        val out = ArrayList<CachedMessage>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, peer_id, is_group, sender_id, type, text, timestamp, status " +
                    "FROM cache_messages WHERE peer_id=? AND is_group=? " +
                    "ORDER BY timestamp DESC LIMIT ?",
            arrayOf(peerId.toString(), (if (isGroup) 1 else 0).toString(), limit.toString())
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                out.add(
                    CachedMessage(
                        id = c.getLong(0),
                        peerId = c.getLong(1),
                        isGroup = c.getInt(2) == 1,
                        senderId = c.getLong(3),
                        type = c.getString(4) ?: "text",
                        text = c.getString(5),
                        timestamp = c.getLong(6),
                        status = c.getString(7) ?: "sent"
                    )
                )
            }
        }
        out.reverse() // a orden cronologico ascendente
        return out
    }

    /** Actualiza el estado de un mensaje (p.ej. sent -> delivered). */
    @Synchronized
    fun updateStatus(localId: Long, status: String) {
        val values = ContentValues().apply { put("status", status) }
        writableDatabase.update("cache_messages", values, "id=?", arrayOf(localId.toString()))
    }

    /**
     * Devuelve el ultimo mensaje de cada conversacion distinta (peer_id +
     * is_group), ordenado por mas reciente primero. Se usa para poblar la
     * lista de chats al abrir la app o al volver de un ChatActivity --
     * antes esa lista solo se llenaba en memoria via upsert() en vivo, asi
     * que se vaciaba (aparentando "no tener chats") cada vez que el proceso
     * se reciclaba o MainActivity se recreaba, aunque los mensajes seguian
     * persistidos aqui en SQLite.
     */
    @Synchronized
    fun conversationSummaries(): List<ConversationSummary> {
        val out = ArrayList<ConversationSummary>()
        val cursor = readableDatabase.rawQuery(
            "SELECT m.peer_id, m.is_group, m.text, m.type, m.timestamp " +
                    "FROM cache_messages m " +
                    "INNER JOIN (" +
                    "  SELECT peer_id, is_group, MAX(timestamp) AS max_ts " +
                    "  FROM cache_messages GROUP BY peer_id, is_group" +
                    ") last ON m.peer_id = last.peer_id AND m.is_group = last.is_group " +
                    "  AND m.timestamp = last.max_ts " +
                    "ORDER BY m.timestamp DESC",
            null
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                out.add(
                    ConversationSummary(
                        peerId = c.getLong(0),
                        isGroup = c.getInt(1) == 1,
                        lastText = c.getString(2),
                        lastType = c.getString(3) ?: "text",
                        lastTimestamp = c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    companion object {
        private const val DB_NAME = "dogmsg_cache.db"
        private const val DB_VERSION = 1
    }
}