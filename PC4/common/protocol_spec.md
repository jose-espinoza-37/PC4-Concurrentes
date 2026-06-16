# Dog Messenger — Especificación del Protocolo de Comunicación

**CC4P1 Programación Concurrente y Distribuida — Práctica 04 2026-I**

> **CONTRATO COMPARTIDO**: Este documento es la referencia canónica para Java (servidor),
> Java (cliente desktop), Kotlin (Android) y Python (módulo ventas).
> Todos los nodos DEBEN serializar y deserializar las tramas exactamente como se indica aquí.
> El campo `Magic = 0xD06D` es el identificador del protocolo; cualquier paquete que
> no comience con este valor debe ser descartado silenciosamente.

---

## 1. Formato de Trama (Sección 4 del plan técnico)

Cada mensaje intercambiado sobre TCP sigue esta estructura binaria **Big-Endian**:

```
┌──────────────┬────────┬──────────────┬──────────────────────────────────────┐
│ Campo        │ Bytes  │ Tipo         │ Descripción                          │
├──────────────┼────────┼──────────────┼──────────────────────────────────────┤
│ Magic        │   2    │ uint16 BE    │ 0xD06D — identificador de protocolo  │
│ Version      │   1    │ uint8        │ 1 (versión actual)                   │
│ Opcode       │   1    │ uint8        │ Tipo de mensaje (ver tabla opcodes)  │
│ Sequence     │   4    │ uint32 BE    │ Número de secuencia (para ACKs)      │
│ Sender ID    │   4    │ uint32 BE    │ ID numérico del remitente            │
│ Receiver ID  │   4    │ uint32 BE    │ ID del destinatario o grupo          │
│ Timestamp    │   8    │ int64  BE    │ Unix timestamp en milisegundos       │
│ Flags        │   1    │ uint8        │ Bit0=encrypted, Bit1=compressed,     │
│              │        │              │ Bit2=is_group                        │
│ Payload Len  │   4    │ uint32 BE    │ Tamaño del payload en bytes          │
├──────────────┼────────┼──────────────┼──────────────────────────────────────┤
│ Payload      │  var   │ bytes        │ Datos del mensaje (ver sección 2)    │
├──────────────┼────────┼──────────────┼──────────────────────────────────────┤
│ Checksum     │   4    │ uint32 BE    │ CRC32 de (header + payload)          │
└──────────────┴────────┴──────────────┴──────────────────────────────────────┘
```

- **Header fijo**: 29 bytes (antes del payload).
- **Total mínimo**: 29 (header) + 0 (payload vacío) + 4 (checksum) = **33 bytes**.
- **Endianness**: **Big-Endian** en todos los campos multi-byte sin excepción.
- **Servidor ID**: El servidor usa `senderId = 0` cuando genera mensajes propios.

### 1.1 Bits del campo Flags

| Bit | Máscara | Nombre      | Significado                                    |
|-----|---------|-------------|------------------------------------------------|
| 0   | 0x01    | encrypted   | El payload está cifrado con AES-256-CBC        |
| 1   | 0x02    | compressed  | El payload está comprimido (reservado, no usar aún) |
| 2   | 0x04    | is_group    | El mensaje es para un grupo (receiverId=groupId) |

### 1.2 Cálculo del Checksum

```
CRC32(header_bytes[0..28] + payload_bytes[0..payloadLen-1])
```

El checksum se calcula sobre **todos los bytes del header** (incluyendo el campo
`Payload Len`) más los bytes del payload. Se escribe como uint32 Big-Endian al
final del paquete, **fuera** del área cubierta por el checksum.

---

## 2. Tabla de Opcodes

| Opcode | Hex  | Nombre         | Dirección    | Descripción                          |
|--------|------|----------------|--------------|--------------------------------------|
| 1      | 0x01 | AUTH_REQUEST   | C → S        | Login o registro                     |
| 2      | 0x02 | AUTH_RESPONSE  | S → C        | Resultado de autenticación + token   |
| 16     | 0x10 | MSG_TEXT       | C → S → C   | Mensaje de texto                     |
| 17     | 0x11 | MSG_IMAGE      | C → S → C   | Imagen (metadata + chunks)           |
| 18     | 0x12 | MSG_FILE       | C → S → C   | Archivo (metadata + chunks)          |
| 19     | 0x13 | MSG_ACK        | S → C        | Confirmación de entrega              |
| 32     | 0x20 | GROUP_CREATE   | C → S        | Crear grupo                          |
| 33     | 0x21 | GROUP_JOIN     | C → S        | Agregar miembro al grupo             |
| 34     | 0x22 | GROUP_LEAVE    | C → S        | Salir / eliminar de grupo            |
| 35     | 0x23 | GROUP_MSG      | C → S → C*  | Mensaje a grupo (broadcast)          |
| 48     | 0x30 | QR_GENERATE    | C → S        | Solicitar token de clonación         |
| 49     | 0x31 | QR_VALIDATE    | C → S        | Validar token QR escaneado           |
| 50     | 0x32 | HISTORY_SYNC   | S → C        | Historial al dispositivo clonado     |
| 64     | 0x40 | FILE_CHUNK     | C ↔ S       | Chunk de archivo (máx. 64 KB)        |
| 65     | 0x41 | FILE_COMPLETE  | C ↔ S       | Transferencia de archivo completada  |
| 80     | 0x50 | KEY_EXCHANGE   | C → S → C   | Intercambio clave pública DH         |
| 96     | 0x60 | SALES_QUERY    | C → S        | Consulta al módulo de ventas         |
| 97     | 0x61 | SALES_RESPONSE | S → C        | Respuesta del módulo de ventas       |
| 240    | 0xF0 | PING           | C ↔ S       | Keep-alive (cada 30 s)               |
| 255    | 0xFF | DISCONNECT     | C → S        | Desconexión limpia                   |

---

## 3. Payloads por Tipo de Mensaje

Todos los payloads de texto usan **UTF-8**. Los payloads JSON no incluyen espacios
en blanco innecesarios para reducir el tamaño de la trama.

### 3.1 AUTH_REQUEST (0x01) — C → S

```json
{
  "action": "login",
  "username": "alice",
  "password_hash": "sha256hexstring",
  "device_type": "desktop"
}
```

- `action`: `"login"` o `"register"`.
- `password_hash`: SHA-256 de la contraseña **en el cliente**. El servidor aplica
  un segundo hash con sal antes de almacenar. La contraseña nunca viaja en texto plano.
- `device_type`: `"desktop"` o `"mobile"`.

### 3.2 AUTH_RESPONSE (0x02) — S → C

**Éxito:**
```json
{"ok":true,"token":"uuid-v4","user_id":42,"username":"alice"}
```

**Error:**
```json
{"ok":false,"error":"Contraseña incorrecta"}
```

### 3.3 MSG_TEXT (0x10) — C → S → C

- **Flags**: `encrypted = 1`.
- **Payload**: bytes del texto cifrado con AES-256-CBC.
  - Formato: `[16 bytes IV][ciphertext bytes]`
  - El receptor descifra con su clave compartida DH.
- **senderId**: ID del remitente (verificado por el servidor).
- **receiverId**: ID del destinatario (usuario o grupo).

### 3.4 MSG_IMAGE (0x11) — C → S → C

**Paquete 1 — Metadata:**
```json
{
  "transfer_id": "abc123def",
  "filename": "foto.jpg",
  "size": 204800,
  "mime_type": "image/jpeg",
  "total_chunks": 4
}
```

**Paquetes 2..N — FILE_CHUNK (0x40):**
- Payload: `transferId|chunkIndex|` seguido de los bytes binarios del chunk.
  - `transferId`: identificador de la transferencia (string).
  - `chunkIndex`: índice base 0 en decimal (string).
  - Separador: carácter `|` (0x7C).
  - El resto del payload son los bytes raw del chunk (máx. 64 KB).

**Paquete final — FILE_COMPLETE (0x41):**
```json
{"transfer_id":"abc123def","checksum_crc32":3456789012}
```

### 3.5 MSG_FILE (0x12) — C → S → C

Idéntico a MSG_IMAGE. El campo `mime_type` diferencia el tipo de contenido.
Límite: **25 MB** por archivo.

### 3.6 MSG_ACK (0x13) — S → C

```json
{"seq":42,"delivered_to":7}
```

- `seq`: número de secuencia del paquete original confirmado.
- `delivered_to`: ID del destinatario final (usuario o grupo).

### 3.7 GROUP_CREATE (0x20) — C → S

```json
{"name":"Equipo Alpha"}
```

**Respuesta MSG_ACK:**
```json
{"ok":true,"group_id":5,"name":"Equipo Alpha"}
```

### 3.8 GROUP_JOIN (0x21) — C → S

```json
{"group_id":5,"target_user_id":12}
```

- Solo el admin del grupo puede agregar otros usuarios.
- Si `target_user_id` se omite, se agrega al propio remitente.

### 3.9 GROUP_LEAVE (0x22) — C → S

```json
{"group_id":5,"target_user_id":12}
```

- Cualquier miembro puede salir (omitir `target_user_id` o igual al propio ID).
- Solo el admin puede expulsar a otros.

### 3.10 GROUP_MSG (0x23) — C → S → C*

- **Flags**: `is_group = 1`, `encrypted = 1`.
- **receiverId**: ID del grupo.
- **Payload**: texto cifrado (mismo formato que MSG_TEXT).
- El servidor hace broadcast a todos los miembros excepto el remitente.

### 3.11 QR_GENERATE (0x30) — C → S

Payload vacío o JSON con metadatos del dispositivo:
```json
{"device_type":"desktop"}
```

**Respuesta del servidor (mismo opcode 0x30):**
```json
{"token":"ABCDEF123456","expires_in_seconds":60}
```

El cliente convierte el `token` en un código QR y lo muestra en pantalla.

### 3.12 QR_VALIDATE (0x31) — C → S

```json
{"token":"ABCDEF123456","device_type":"mobile"}
```

**Respuesta del servidor (mismo opcode 0x31):**
```json
{"ok":true,"message":"Sesión clonada. Sincronizando historial..."}
```

Inmediatamente después el servidor envía HISTORY_SYNC (0x32).

### 3.13 HISTORY_SYNC (0x32) — S → C

```json
[
  {"id":1,"from":3,"to":7,"type":"text","ts":"2026-04-01 10:00:00"},
  {"id":2,"from":7,"to":3,"type":"image","ts":"2026-04-01 10:01:00"}
]
```

Contiene los últimos 500 mensajes del usuario clonado en orden cronológico.

### 3.14 KEY_EXCHANGE (0x50) — C → S → C

- **Payload**: bytes de la clave pública Diffie-Hellman del remitente.
  (formato SubjectPublicKeyInfo / raw bytes según implementación del cliente).
- **senderId**: usuario que envía su clave pública.
- **receiverId**: usuario con quien desea comunicarse cifrado.
- El servidor almacena la clave en `public_keys` y la retransmite al destinatario.

### 3.15 PING / PONG (0xF0) — C ↔ S

- Payload vacío.
- El cliente envía PING cada **30 segundos**.
- Si el servidor no recibe PING en **90 segundos**, cierra la conexión.
- El servidor responde con PING (mismo opcode, funcionando como PONG).

### 3.16 DISCONNECT (0xFF) — C → S

- Payload vacío.
- El cliente notifica al servidor antes de cerrar el socket.
- El servidor elimina la sesión y libera recursos.

### 3.17 SALES_QUERY (0x60) / SALES_RESPONSE (0x61)

El nodo de ventas Python se conecta como un cliente normal con AUTH_REQUEST.
Los mensajes de ventas usan MSG_TEXT estándar con prefijo de comando:

```
/catalogo
/buscar producto
/pedir producto 3
/mis_pedidos
/estado ORD-1001
/reporte diario
```

SALES_QUERY y SALES_RESPONSE son opcionales para comunicación directa
servidor ↔ nodo de ventas si se requieren métricas globales.

---

## 4. Flujos Completos

### 4.1 Registro y Login

```
Cliente                          Servidor
   │                                 │
   │── AUTH_REQUEST (register) ─────►│
   │                                 │  createUser() → SQLite
   │◄─ AUTH_RESPONSE (ok, token) ────│
   │                                 │
   │── PING (cada 30s) ─────────────►│
   │◄─ PING ─────────────────────────│
```

### 4.2 Mensaje de Texto Privado

```
Cliente A           Servidor            Cliente B
   │                   │                    │
   │── MSG_TEXT ───────►│                   │
   │                   │── MSG_TEXT ────────►│  (si online)
   │                   │── offline_queue     │  (si offline)
   │◄─ MSG_ACK ─────────│                   │
```

### 4.3 Transferencia de Archivo

```
Cliente A           Servidor            Cliente B
   │                   │                    │
   │── MSG_FILE(meta) ─►│                   │
   │                   │── MSG_FILE(meta) ──►│
   │── FILE_CHUNK x N ─►│                   │
   │                   │── FILE_CHUNK x N ──►│
   │── FILE_COMPLETE ──►│                   │
   │                   │── FILE_COMPLETE ───►│
   │◄─ MSG_ACK ─────────│                   │
```

### 4.4 Clonación QR

```
Desktop (A)         Servidor            Móvil (B)
   │                   │                    │
   │── QR_GENERATE ───►│                   │
   │◄─ QR_GENERATE ────│  (token en resp)  │
   │  [muestra QR]     │                   │
   │                   │◄── QR_VALIDATE ───│  (B escanea QR)
   │                   │─── QR_VALIDATE ───►│  (ok)
   │                   │─── HISTORY_SYNC ──►│  (historial de A)
```

### 4.5 Intercambio de Claves E2E

```
Cliente A           Servidor            Cliente B
   │                   │                    │
   │── KEY_EXCHANGE ──►│ (clave pub A)     │
   │                   │── KEY_EXCHANGE ────►│ (clave pub A → B)
   │                   │◄── KEY_EXCHANGE ───│ (clave pub B)
   │◄─ KEY_EXCHANGE ───│ (clave pub B → A) │
   │                   │                    │
   │  [A deriva AES]   │                    │  [B deriva AES]
   │  shared = DH(privA, pubB)              │  shared = DH(privB, pubA)
   │                   │                    │
   │── MSG_TEXT(cifrado AES) ──────────────►│
```

---

## 5. Implementación por Lenguaje

### Java (servidor y cliente desktop)

```java
// Serialización Big-Endian con ByteBuffer
ByteBuffer buf = ByteBuffer.allocate(totalSize);
buf.order(ByteOrder.BIG_ENDIAN);
buf.putShort((short) 0xD06D);   // Magic
buf.put((byte) 1);              // Version
buf.put((byte) opcode);         // Opcode
buf.putInt(sequence);           // Sequence
buf.putInt(senderId);           // Sender ID
buf.putInt(receiverId);         // Receiver ID
buf.putLong(timestamp);         // Timestamp
buf.put(flags);                 // Flags
buf.putInt(payloadLen);         // Payload Length
buf.put(payload);               // Payload
// Calcular CRC32 y agregar al final
```

### Python (módulo de ventas)

```python
import struct, zlib

MAGIC   = 0xD06D
VERSION = 1
HEADER_FORMAT = '>HBBIIIqBI'   # Big-Endian
HEADER_SIZE   = 29             # bytes

def pack_packet(opcode, sender_id, receiver_id, payload=b'', flags=0, seq=0):
    ts   = int(time.time() * 1000)
    hdr  = struct.pack(HEADER_FORMAT,
                       MAGIC, VERSION, opcode, seq,
                       sender_id, receiver_id, ts,
                       flags, len(payload))
    body = hdr + payload
    crc  = zlib.crc32(body) & 0xFFFFFFFF
    return body + struct.pack('>I', crc)

def unpack_header(data):
    magic, version, opcode, seq, sender, receiver, ts, flags, paylen = \
        struct.unpack(HEADER_FORMAT, data[:HEADER_SIZE])
    if magic != MAGIC:
        raise ValueError(f'Magic inválido: {magic:#06x}')
    return opcode, seq, sender, receiver, ts, flags, paylen
```

### Kotlin (Android)

```kotlin
// Idéntico al cliente Java — usar java.nio.ByteBuffer con BIG_ENDIAN
val buf = ByteBuffer.allocate(totalSize).apply {
    order(ByteOrder.BIG_ENDIAN)
    putShort(0xD06D.toShort())
    put(1.toByte())
    put(opcode.toByte())
    putInt(sequence)
    putInt(senderId)
    putInt(receiverId)
    putLong(timestamp)
    put(flags)
    putInt(payload.size)
    put(payload)
}
val crc = CRC32().also { it.update(buf.array(), 0, HEADER_SIZE + payload.size) }
buf.putInt((crc.value and 0xFFFFFFFFL).toInt())
```

---

## 6. Restricciones del Enunciado

- **Sin WebSocket, Socket.IO, RabbitMQ** ni ningún framework de mensajería.
- **Solo sockets TCP nativos**: `java.net.Socket` (Java/Kotlin), `socket` (Python).
- **Hilos para concurrencia**: `Thread`/`ExecutorService` (Java), `threading` (Python),
  corrutinas o `Thread` (Kotlin).
- **Despliegue en LAN y WiFi real**: cluster de nodos físicos o virtuales.
