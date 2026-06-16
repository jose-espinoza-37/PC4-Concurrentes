# Dog Messenger — Servidor Java (LP1 / Persona A)

**CC4P1 Programación Concurrente y Distribuida — Práctica 04 2026-I**

---

## Estructura de archivos

```
server/
├── build.gradle
├── settings.gradle
├── README.md
└── src/main/java/dogmsg/
    ├── Server.java               ← Entry point (main), ServerSocket, loops de aceptación
    ├── ClientHandler.java        ← Hilo por conexión TCP, despacho de opcodes
    ├── AuthManager.java          ← Registro, login, tokens de sesión multi-dispositivo
    ├── MessageRouter.java        ← Enrutamiento MSG_TEXT / MSG_IMAGE / GROUP_MSG
    ├── GroupManager.java         ← CRUD de grupos, permisos admin, broadcast
    ├── FileTransferHandler.java  ← Chunks 64KB, metadata, FILE_COMPLETE con CRC32
    ├── OfflineQueue.java         ← Cola persistente para usuarios desconectados
    ├── EncryptionBroker.java     ← Relay de claves públicas Diffie-Hellman (E2E)
    ├── QRManager.java            ← Tokens QR de 60 s, clonación de sesiones
    ├── DatabaseManager.java      ← SQLite: users, sessions, messages, groups, files, keys
    └── protocol/
        ├── OpCode.java           ← Enum de todos los opcodes (0x01 … 0xFF)
        ├── Packet.java           ← Serialización binaria Big-Endian (29 bytes header)
        └── PacketParser.java     ← Lectura desde InputStream, validación CRC32 y Magic
```

---

## Compilar y ejecutar

### Requisitos

- **Java 17+**
- **Gradle 8+** (o usar el wrapper `gradlew` incluido)

### Compilar

```bash
cd server/
./gradlew build
```

Genera `build/libs/server.jar` (fat JAR con SQLite incluido).

### Ejecutar

```bash
# Con Gradle (recomendado en desarrollo — habilita la consola admin)
./gradlew run

# Con argumentos personalizados
./gradlew run --args="--port 9000 --file-port 9001"

# Directamente con Java (producción / cluster)
java -jar build/libs/server.jar --port 9000 --file-port 9001
```

### Consola de administración

Al iniciar, el servidor abre una consola en stdin:

```
dog9000> status     # muestra clientes online y grupos activos
dog9000> users      # lista usuarios conectados con su ID
dog9000> stop       # apaga el servidor limpiamente
dog9000> help       # esta ayuda
```

---

## Despliegue en LAN / WiFi (cluster)

1. **PC servidor**: anotar la IP local (ej. `192.168.1.100`).
2. Ejecutar el servidor:
   ```bash
   java -jar server.jar --port 9000 --file-port 9001
   ```
3. **Clientes desktop Java** (otra PC de la misma red):
   ```bash
   # En el cliente, configurar IP = 192.168.1.100, puerto = 9000
   java -jar client-desktop.jar
   ```
4. **Clientes Android** (celular en la misma WiFi):
   - Instalar el APK y configurar IP `192.168.1.100`, puerto `9000`.
5. **Nodo de ventas Python** (cualquier PC de la red):
   ```bash
   python sales_node.py --server 192.168.1.100 --port 9000
   ```

---

## Protocolo

Ver [`../common/protocol_spec.md`](../common/protocol_spec.md) para:
- Formato de trama binaria completo (29 bytes header + payload + CRC32).
- Tabla de opcodes.
- Payloads JSON por tipo de mensaje.
- Flujos completos (auth, mensajes, archivos, QR, E2E).
- Implementaciones en Java, Kotlin y Python.

---

## Seguridad

| Aspecto              | Implementación                                                         |
|----------------------|------------------------------------------------------------------------|
| Contraseñas          | `SHA-256(SHA-256(password) + salt)` — el cliente hashea antes de enviar |
| Tokens de sesión     | UUID v4, expiran a las 24 h sin actividad (PING cada 30 s)            |
| Encriptación E2E     | Diffie-Hellman para intercambio de clave → AES-256-CBC por mensaje     |
| Servidor ciego       | Almacena y enruta mensajes cifrados sin poder descifrarlos             |
| Magic + CRC32        | Detección de paquetes corruptos o conexiones externas                  |
| Rate limiting        | Máx. 100 paquetes/segundo por cliente; exceso se descarta             |

---

## Base de datos

SQLite en archivo `dogmessenger.db` (mismo directorio de ejecución).

Tablas: `users`, `sessions`, `messages`, `groups`, `group_members`,
`offline_queue`, `files`, `public_keys`.

Esquema completo en [`../common/db_schema.sql`](../common/db_schema.sql).
