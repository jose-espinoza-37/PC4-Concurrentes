-- ==============================================================
-- Dog Messenger — Esquema SQLite (sección 9 del plan técnico)
-- Servidor Java (Persona A)
-- ==============================================================

PRAGMA journal_mode = WAL;       -- concurrencia de lecturas
PRAGMA foreign_keys = ON;        -- integridad referencial

-- ── Usuarios ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    UNIQUE NOT NULL,
    password_hash TEXT    NOT NULL,          -- SHA-256(SHA-256(password)+salt)
    salt          TEXT    NOT NULL,          -- 16 bytes aleatorios en hex
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- ── Sesiones activas (multi-dispositivo) ──────────────────────
-- Un mismo usuario puede tener N sesiones simultáneas.
CREATE TABLE IF NOT EXISTS sessions (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       TEXT    UNIQUE NOT NULL,     -- UUID v4
    device_type TEXT    CHECK(device_type IN ('desktop','mobile')),
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    last_ping   DATETIME                     -- actualizado cada PING (30s)
);

-- ── Mensajes ──────────────────────────────────────────────────
-- El campo content almacena el payload cifrado; el servidor no lo descifra.
CREATE TABLE IF NOT EXISTS messages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    sender_id   INTEGER NOT NULL REFERENCES users(id),
    receiver_id INTEGER NOT NULL,            -- user_id O group_id
    is_group    BOOLEAN DEFAULT 0,
    msg_type    TEXT    NOT NULL CHECK(msg_type IN ('text','image','file')),
    content     BLOB,                        -- bytes cifrados AES-256
    status      TEXT    DEFAULT 'sent'
                        CHECK(status IN ('sent','delivered','read')),
    timestamp   DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- ── Grupos ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS groups (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL,
    admin_id   INTEGER NOT NULL REFERENCES users(id),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- ── Miembros de grupo ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS group_members (
    group_id  INTEGER NOT NULL REFERENCES groups(id)  ON DELETE CASCADE,
    user_id   INTEGER NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (group_id, user_id)
);

-- ── Cola de mensajes offline ──────────────────────────────────
-- Paquetes serializados pendientes de entrega para usuarios desconectados.
CREATE TABLE IF NOT EXISTS offline_queue (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    packet_data BLOB    NOT NULL,            -- Packet.serialize() completo
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- ── Archivos transferidos ─────────────────────────────────────
CREATE TABLE IF NOT EXISTS files (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id   INTEGER REFERENCES messages(id) ON DELETE SET NULL,
    filename     TEXT    NOT NULL,
    mime_type    TEXT,
    size_bytes   INTEGER,
    storage_path TEXT,                       -- ruta relativa en uploads/
    uploaded_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- ── Claves públicas Diffie-Hellman (para E2E) ─────────────────
-- El servidor almacena las claves PÚBLICAS para retransmitirlas.
-- Las claves privadas NUNCA llegan al servidor.
CREATE TABLE IF NOT EXISTS public_keys (
    user_id    INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    public_key BLOB    NOT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- ==============================================================
-- Índices para búsquedas frecuentes
-- ==============================================================
CREATE INDEX IF NOT EXISTS idx_messages_sender    ON messages(sender_id);
CREATE INDEX IF NOT EXISTS idx_messages_receiver  ON messages(receiver_id);
CREATE INDEX IF NOT EXISTS idx_messages_timestamp ON messages(timestamp);
CREATE INDEX IF NOT EXISTS idx_sessions_user      ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_token     ON sessions(token);
CREATE INDEX IF NOT EXISTS idx_offline_user       ON offline_queue(user_id);
CREATE INDEX IF NOT EXISTS idx_offline_created    ON offline_queue(created_at);
CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members(user_id);
