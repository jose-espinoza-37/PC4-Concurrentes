# Dog Messenger

**CC4P1 Programacion Concurrente y Distribuida — Practica 04 2026-I**

Sistema de mensajeria distribuida con servidor Java, clientes desktop/Android y modulo de ventas Python. Comunicacion mediante protocolo binario propio sobre TCP (sockets nativos).

---

## Estructura del proyecto

```
PC4/
├── README.md
├── common/
│   ├── protocol_spec.md          ← Especificacion del protocolo binario
│   └── db_schema.sql             ← Esquema SQLite del servidor
├── server/                        ← Persona A — Servidor Java
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/main/java/dogmsg/
│       ├── Server.java
│       ├── ClientHandler.java
│       ├── AuthManager.java
│       ├── MessageRouter.java
│       ├── GroupManager.java
│       ├── FileTransferHandler.java
│       ├── OfflineQueue.java
│       ├── EncryptionBroker.java
│       ├── QRManager.java
│       ├── DatabaseManager.java
│       └── protocol/
│           ├── OpCode.java
│           ├── Packet.java
│           └── PacketParser.java
├── client-desktop/                ← Persona B — Cliente Desktop Java
│   ├── build.gradle
│   └── src/main/java/dogmsg/client/
│       ├── DogMessengerApp.java
│       ├── SocketManager.java
│       ├── ChatController.java
│       ├── CryptoManager.java
│       ├── LocalCache.java
│       ├── QRManager.java
│       └── ui/
│           ├── LoginView.java
│           ├── ChatListView.java
│           ├── ChatWindow.java
│           └── GroupDialog.java
├── client-android/                ← Persona B — Cliente Android Kotlin
│   ├── build.gradle.kts
│   └── app/src/main/java/
│       ├── MainActivity.kt
│       ├── ChatActivity.kt
│       ├── SocketService.kt
│       ├── QRScanActivity.kt
│       └── CryptoManager.kt
└── sales-module/                  ← Persona C — Modulo de Ventas Python
    ├── sales_node.py
    ├── db.py
    ├── catalog.py
    ├── order_manager.py
    ├── receipt_generator.py
    └── metrics.py
```

---

## Division del trabajo

| Persona | Componente | Lenguaje | Tareas |
|---------|-----------|----------|--------|
| A | Servidor | Java 17 | T-A1 a T-A10: ServerSocket, hilos, auth, routing, grupos, archivos, offline, E2E, DB, protocolo |
| B | Clientes | Java / Kotlin | T-B1 a T-B10: Desktop y Android, UI, sockets, crypto, QR, cache |
| C | Ventas | Python 3 | T-C1 a T-C6: Nodo TCP, DB local, catalogo, pedidos, recibos, metricas |

---

## Protocolo

Protocolo binario Big-Endian sobre TCP. Ver [`common/protocol_spec.md`](common/protocol_spec.md).

- **Magic**: `0xD06D`
- **Header**: 29 bytes fijos
- **Checksum**: CRC32 al final del paquete
- **Restricciones**: Solo sockets TCP nativos (`java.net.Socket`, `socket` Python). Sin WebSocket, Socket.IO, ni frameworks de mensajeria.

---

## Servidor (Persona A)

### Requisitos

- Java 17+
- Gradle 8+

### Compilar y ejecutar

```bash
cd server/
./gradlew build
./gradlew run --args="--port 9000 --file-port 9001"

# O directamente:
java -jar build/libs/server.jar --port 9000 --file-port 9001
```

### Consola de administracion

```
dog9000> status     # clientes online y grupos activos
dog9000> users      # usuarios conectados
dog9000> stop       # apagar servidor
dog9000> help       # ayuda
```

---

## Modulo de Ventas (Persona C)

### Requisitos

- Python 3.8+
- Sin dependencias externas (usa solo libreria estandar)

### Ejecutar

```bash
cd sales-module/
python3 sales_node.py --host 127.0.0.1 --port 9000
```

El nodo se conecta al servidor como un cliente normal (`ventas_bot`), se autentica automaticamente y responde a mensajes de cualquier usuario.

### Comandos disponibles

| Comando | Descripcion |
|---------|-------------|
| `/catalogo` | Listar todos los productos |
| `/buscar <texto>` | Buscar por nombre, descripcion o categoria |
| `/pedir <id> [cantidad]` | Crear un pedido |
| `/mis_pedidos` | Ver pedidos del usuario |
| `/estado <ORD-XXXX>` | Detalle de un pedido |
| `/reporte [diario]` | Reporte de ventas del dia |
| `/confirmar <ORD-XXXX>` | Confirmar pedido (admin) |
| `/entregar <ORD-XXXX>` | Marcar como entregado (admin) |
| `/ayuda` | Mostrar ayuda |

### Arquitectura

```
sales_node.py          Nodo TCP principal (hilo lector + hilo PING)
    ├── db.py          SQLite local (products, orders, order_items, daily_sales)
    ├── catalog.py     CRUD de productos, busqueda, control de stock
    ├── order_manager.py   Gestion de pedidos (PENDING -> CONFIRMED -> DELIVERED)
    ├── receipt_generator.py   Recibos en texto formateado ASCII
    └── metrics.py     Reportes diarios de ventas
```

### Base de datos local

SQLite en `sales.db` (se crea automaticamente). Incluye 10 productos de ejemplo pre-cargados al iniciar.

---

## Despliegue en LAN / WiFi

1. **PC servidor** — anotar IP local (ej. `192.168.1.100`):
   ```bash
   cd server/ && java -jar build/libs/server.jar --port 9000 --file-port 9001
   ```

2. **Cliente desktop** (otra PC en la misma red):
   ```bash
   cd client-desktop/ && java -jar build/libs/client-desktop.jar
   ```

3. **Cliente Android** (celular en la misma WiFi):
   - Instalar APK, configurar IP y puerto del servidor.

4. **Nodo de ventas** (cualquier PC de la red):
   ```bash
   cd sales-module/ && python3 sales_node.py --host 192.168.1.100 --port 9000
   ```

---

## Seguridad

| Aspecto | Implementacion |
|---------|---------------|
| Contrasenas | `SHA-256(SHA-256(password) + salt)` — cliente hashea antes de enviar |
| Tokens | UUID v4, expiran a las 24h sin actividad |
| Cifrado E2E | Diffie-Hellman + AES-256-CBC por mensaje |
| Servidor ciego | Enruta mensajes cifrados sin descifrarlos |
| Integridad | Magic `0xD06D` + CRC32 por paquete |
| Rate limiting | Max 100 paquetes/segundo por cliente |
