"""
sales_node.py
Dog Messenger — Modulo de Ventas
Nodo TCP que se conecta al servidor como cliente normal.

Protocolo binario: Magic 0xD06D, header 29 bytes, CRC32.
Hilo lector + hilo escritor + hilo PING keep-alive.

Comandos soportados (recibidos via MSG_TEXT):
  /catalogo           — listar productos
  /buscar <texto>     — buscar productos
  /pedir <id> [cant]  — crear pedido
  /mis_pedidos        — listar pedidos del usuario
  /estado <ORD-XXXX>  — detalle de un pedido
  /reporte [diario]   — reporte de ventas
  /confirmar <ORD-X>  — confirmar pedido (admin)
  /entregar <ORD-X>   — marcar como entregado (admin)
"""

import socket
import struct
import zlib
import json
import threading
import time
import sys
import hashlib
import logging

import db
import catalog
import order_manager
import metrics

# -- Configuracion -----------------------------------------------------------

SERVER_HOST = "127.0.0.1"
SERVER_PORT = 9000
BOT_USERNAME = "ventas_bot"
BOT_PASSWORD = "ventas123"

# -- Protocolo binario -------------------------------------------------------

MAGIC = 0xD06D
VERSION = 1
HEADER_FORMAT = ">HBBIIIqBI"  # Big-Endian
HEADER_SIZE = 29

# Opcodes
OP_AUTH_REQUEST  = 0x01
OP_AUTH_RESPONSE = 0x02
OP_MSG_TEXT      = 0x10
OP_MSG_ACK       = 0x13
OP_SALES_QUERY   = 0x60
OP_SALES_RESPONSE = 0x61
OP_PING          = 0xF0
OP_DISCONNECT    = 0xFF

# -- Logging -----------------------------------------------------------------

logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] %(levelname)s — %(message)s",
    datefmt="%H:%M:%S"
)
log = logging.getLogger("sales_node")

# -- Estado global -----------------------------------------------------------

_seq_counter = 0
_seq_lock = threading.Lock()
_socket_lock = threading.Lock()
_running = True
_my_user_id = 0
_my_token = ""
_sock = None


def _next_seq():
    global _seq_counter
    with _seq_lock:
        _seq_counter += 1
        return _seq_counter


# -- Serializar / Deserializar paquetes --------------------------------------

def pack_packet(opcode, receiver_id, payload=b"", flags=0):
    """Empaqueta un paquete binario siguiendo el protocolo Dog Messenger."""
    seq = _next_seq()
    ts = int(time.time() * 1000)
    hdr = struct.pack(
        HEADER_FORMAT,
        MAGIC, VERSION, opcode, seq,
        _my_user_id, receiver_id, ts,
        flags, len(payload)
    )
    body = hdr + payload
    crc = zlib.crc32(body) & 0xFFFFFFFF
    return body + struct.pack(">I", crc)


def read_packet(sock):
    """Lee un paquete completo del socket. Retorna (opcode, sender_id, receiver_id, flags, payload) o None."""
    header_data = _read_exactly(sock, HEADER_SIZE)
    if header_data is None:
        return None

    magic, version, opcode, seq, sender_id, receiver_id, ts, flags, payload_len = \
        struct.unpack(HEADER_FORMAT, header_data)

    if magic != MAGIC:
        log.warning(f"Magic invalido: {magic:#06x}")
        return None

    payload = b""
    if payload_len > 0:
        payload = _read_exactly(sock, payload_len)
        if payload is None:
            return None

    # Leer y verificar CRC32
    crc_data = _read_exactly(sock, 4)
    if crc_data is None:
        return None
    received_crc = struct.unpack(">I", crc_data)[0]
    expected_crc = zlib.crc32(header_data + payload) & 0xFFFFFFFF
    if received_crc != expected_crc:
        log.warning("CRC32 no coincide, paquete corrupto")
        return None

    return opcode, sender_id, receiver_id, flags, payload


def _read_exactly(sock, n):
    """Lee exactamente n bytes del socket."""
    data = bytearray()
    while len(data) < n:
        try:
            chunk = sock.recv(n - len(data))
            if not chunk:
                return None
            data.extend(chunk)
        except (ConnectionError, OSError):
            return None
    return bytes(data)


def send_packet(sock, opcode, receiver_id, payload=b"", flags=0):
    """Envia un paquete al servidor de forma thread-safe."""
    pkt = pack_packet(opcode, receiver_id, payload, flags)
    with _socket_lock:
        try:
            sock.sendall(pkt)
        except (ConnectionError, OSError) as e:
            log.error(f"Error enviando paquete: {e}")


# -- Autenticacion -----------------------------------------------------------

def authenticate(sock):
    """Envia AUTH_REQUEST y espera AUTH_RESPONSE. Retorna True si exitoso."""
    global _my_user_id, _my_token

    password_hash = hashlib.sha256(BOT_PASSWORD.encode()).hexdigest()
    auth_payload = json.dumps({
        "action": "login",
        "username": BOT_USERNAME,
        "password_hash": password_hash,
        "device_type": "desktop"
    }).encode("utf-8")

    send_packet(sock, OP_AUTH_REQUEST, 0, auth_payload)
    log.info(f"AUTH_REQUEST enviado como '{BOT_USERNAME}'")

    # Esperar respuesta
    result = read_packet(sock)
    if result is None:
        log.error("No se recibio respuesta de autenticacion")
        return False

    opcode, sender_id, receiver_id, flags, payload = result
    if opcode != OP_AUTH_RESPONSE:
        log.error(f"Se esperaba AUTH_RESPONSE, se recibio opcode {opcode:#04x}")
        return False

    resp = json.loads(payload.decode("utf-8"))
    if resp.get("ok"):
        _my_user_id = resp["user_id"]
        _my_token = resp.get("token", "")
        log.info(f"Autenticado como user_id={_my_user_id}")
        return True
    else:
        error = resp.get("error", "Error desconocido")
        log.warning(f"Autenticacion fallida: {error}")
        # Intentar registrar
        log.info("Intentando registrar el bot...")
        reg_payload = json.dumps({
            "action": "register",
            "username": BOT_USERNAME,
            "password_hash": password_hash,
            "device_type": "desktop"
        }).encode("utf-8")
        send_packet(sock, OP_AUTH_REQUEST, 0, reg_payload)

        result = read_packet(sock)
        if result is None:
            return False
        opcode, sender_id, receiver_id, flags, payload = result
        resp = json.loads(payload.decode("utf-8"))
        if resp.get("ok"):
            _my_user_id = resp["user_id"]
            _my_token = resp.get("token", "")
            log.info(f"Registrado y autenticado como user_id={_my_user_id}")
            return True
        log.error(f"Registro fallido: {resp.get('error')}")
        return False


# -- Despacho de comandos ----------------------------------------------------

def dispatch_command(sender_id, text):
    """Procesa un comando de texto y retorna la respuesta."""
    text = text.strip()
    if not text.startswith("/"):
        return None  # No es un comando

    parts = text.split(maxsplit=1)
    cmd = parts[0].lower()
    args = parts[1] if len(parts) > 1 else ""

    try:
        if cmd == "/catalogo":
            return catalog.handle_catalogo()
        elif cmd == "/buscar":
            return catalog.handle_buscar(args)
        elif cmd == "/pedir":
            return order_manager.handle_pedir(sender_id, args)
        elif cmd == "/mis_pedidos":
            return order_manager.handle_mis_pedidos(sender_id)
        elif cmd == "/estado":
            return order_manager.handle_estado(args)
        elif cmd == "/reporte":
            return metrics.handle_reporte(args)
        elif cmd == "/confirmar":
            return order_manager.confirm_order(args.strip().upper())
        elif cmd == "/entregar":
            return order_manager.deliver_order(args.strip().upper())
        elif cmd == "/ayuda":
            return _help_text()
        else:
            return (
                f"Comando desconocido: {cmd}\n"
                "Escribe /ayuda para ver los comandos disponibles."
            )
    except Exception as e:
        log.error(f"Error procesando comando '{text}': {e}")
        return f"Error interno procesando '{cmd}'. Intenta de nuevo."


def _help_text():
    return (
        "=== COMANDOS DISPONIBLES ===\n"
        "\n"
        "  /catalogo              — Ver todos los productos\n"
        "  /buscar <texto>        — Buscar productos\n"
        "  /pedir <id> [cant]     — Hacer un pedido\n"
        "  /mis_pedidos           — Ver tus pedidos\n"
        "  /estado <ORD-XXXX>     — Detalle de un pedido\n"
        "  /reporte [diario]      — Reporte de ventas\n"
        "  /confirmar <ORD-XXXX>  — Confirmar pedido\n"
        "  /entregar <ORD-XXXX>   — Marcar como entregado\n"
        "  /ayuda                 — Mostrar esta ayuda\n"
    )


# -- Hilos de trabajo --------------------------------------------------------

def reader_thread(sock):
    """Hilo lector: recibe paquetes del servidor y despacha comandos."""
    global _running

    while _running:
        result = read_packet(sock)
        if result is None:
            log.warning("Conexion perdida con el servidor")
            _running = False
            break

        opcode, sender_id, receiver_id, flags, payload = result

        if opcode == OP_PING:
            # Responder PONG
            send_packet(sock, OP_PING, 0)

        elif opcode == OP_MSG_TEXT:
            try:
                # El payload puede ser texto plano o cifrado
                # Para el bot de ventas, asumimos texto sin cifrar (flag encrypted=0)
                if flags & 0x01:
                    # Mensaje cifrado — el bot no puede descifrarlo, ignorar
                    log.debug(f"Mensaje cifrado de user {sender_id}, ignorado")
                    continue
                text = payload.decode("utf-8")
                log.info(f"Mensaje de user {sender_id}: {text}")
            except UnicodeDecodeError:
                log.warning(f"Payload no es UTF-8 de user {sender_id}")
                continue

            response = dispatch_command(sender_id, text)
            if response:
                resp_payload = response.encode("utf-8")
                send_packet(sock, OP_MSG_TEXT, sender_id, resp_payload)
                log.info(f"Respuesta enviada a user {sender_id}")

        elif opcode == OP_SALES_QUERY:
            # Consulta directa de metricas del servidor
            try:
                text = payload.decode("utf-8")
                response = dispatch_command(sender_id, text)
                if response:
                    resp_payload = response.encode("utf-8")
                    send_packet(sock, OP_SALES_RESPONSE, sender_id, resp_payload)
            except Exception as e:
                log.error(f"Error en SALES_QUERY: {e}")

        elif opcode == OP_MSG_ACK:
            log.debug(f"ACK recibido: {payload}")

        else:
            log.debug(f"Opcode no manejado: {opcode:#04x}")


def ping_thread(sock):
    """Hilo keep-alive: envia PING cada 30 segundos."""
    global _running
    while _running:
        time.sleep(30)
        if _running:
            send_packet(sock, OP_PING, 0)
            log.debug("PING enviado")


# -- Main --------------------------------------------------------------------

def main():
    global _running, _sock

    # Parsear argumentos
    host = SERVER_HOST
    port = SERVER_PORT
    for i, arg in enumerate(sys.argv[1:], 1):
        if arg == "--host" and i < len(sys.argv) - 1:
            host = sys.argv[i + 1]
        elif arg == "--port" and i < len(sys.argv) - 1:
            port = int(sys.argv[i + 1])

    # Inicializar base de datos local
    db.init_db()
    log.info("Base de datos de ventas inicializada")

    # Conectar al servidor
    log.info(f"Conectando a {host}:{port}...")
    try:
        _sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        _sock.connect((host, port))
        log.info("Conectado al servidor")
    except ConnectionError as e:
        log.error(f"No se pudo conectar: {e}")
        sys.exit(1)

    # Autenticar
    if not authenticate(_sock):
        log.error("No se pudo autenticar. Saliendo.")
        _sock.close()
        sys.exit(1)

    # Lanzar hilos
    t_reader = threading.Thread(target=reader_thread, args=(_sock,), daemon=True, name="reader")
    t_ping = threading.Thread(target=ping_thread, args=(_sock,), daemon=True, name="ping")

    t_reader.start()
    t_ping.start()

    log.info("Nodo de ventas activo. Esperando comandos...")
    log.info("Presiona Ctrl+C para detener.")

    # Esperar hasta interrupcion
    try:
        while _running:
            time.sleep(1)
    except KeyboardInterrupt:
        log.info("Deteniendo nodo de ventas...")

    # Desconectar limpiamente
    _running = False
    try:
        send_packet(_sock, OP_DISCONNECT, 0)
    except Exception:
        pass
    _sock.close()
    log.info("Nodo de ventas detenido.")


if __name__ == "__main__":
    main()
