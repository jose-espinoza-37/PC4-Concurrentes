"""
sales_node.py
Dog Messenger — Modulo de Ventas
Nodo TCP que se conecta al servidor como cliente normal.

Protocolo binario: Magic 0xD06D, header 29 bytes, CRC32.
Hilo lector + hilo worker (cola) + hilo PING keep-alive.
Soporte de transferencia de archivos (puerto 9001), grupos y keywords.

Comandos soportados (recibidos via MSG_TEXT o keywords):
  /catalogo           — listar productos
  /buscar <texto>     — buscar productos
  /precio <id|nombre> — ver precio de un producto
  /pedir <id> [cant]  — crear pedido
  /mis_pedidos        — listar pedidos del usuario
  /estado <ORD-XXXX>  — detalle de un pedido
  /reporte [diario|semanal|mensual] — reporte de ventas
  /confirmar <ORD-X>  — confirmar pedido (admin)
  /entregar <ORD-X>   — marcar como entregado (admin)
  /grupo_crear <nombre>             — crear grupo
  /grupo_agregar <gid> <user_id>    — agregar miembro
  /grupo_salir <gid>                — salir de un grupo
  /ayuda              — mostrar esta ayuda
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
import uuid
import math
import queue as queue_mod

import db
import catalog
import order_manager
import metrics
import receipt_generator

# -- Configuracion -----------------------------------------------------------

SERVER_HOST = "127.0.0.1"
SERVER_PORT = 9000
FILE_PORT   = 9001
BOT_USERNAME = "ventas_bot"
BOT_PASSWORD = "ventas123"

CHUNK_SIZE = 64 * 1024  # 64 KB
MAX_FILE_SIZE = 25 * 1024 * 1024  # 25 MB

# -- Protocolo binario -------------------------------------------------------

MAGIC = 0xD06D
VERSION = 1
HEADER_FORMAT = ">HBBIIIqBI"  # Big-Endian
HEADER_SIZE = 29

# Opcodes
OP_AUTH_REQUEST    = 0x01
OP_AUTH_RESPONSE   = 0x02
OP_MSG_TEXT        = 0x10
OP_MSG_IMAGE       = 0x11
OP_MSG_FILE        = 0x12
OP_MSG_ACK         = 0x13
OP_GROUP_CREATE    = 0x20
OP_GROUP_JOIN      = 0x21
OP_GROUP_LEAVE     = 0x22
OP_GROUP_MSG       = 0x23
OP_FILE_CHUNK      = 0x40
OP_FILE_COMPLETE   = 0x41
OP_KEY_EXCHANGE    = 0x50
OP_SALES_QUERY     = 0x60
OP_SALES_RESPONSE  = 0x61
OP_PING            = 0xF0
OP_DISCONNECT      = 0xFF

FLAG_ENCRYPTED  = 0x01
FLAG_IS_GROUP   = 0x04

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
_reconnect = False
_my_user_id = 0
_my_token = ""
_sock = None
_password_hash = ""
_msg_queue = queue_mod.Queue()

# Host/port guardados para reconexion y file transfer
_host = SERVER_HOST
_port = SERVER_PORT


def _next_seq():
    global _seq_counter
    with _seq_lock:
        _seq_counter += 1
        return _seq_counter


# -- Serializar / Deserializar paquetes --------------------------------------

def pack_packet(opcode, receiver_id, payload=b"", flags=0):
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
    pkt = pack_packet(opcode, receiver_id, payload, flags)
    with _socket_lock:
        try:
            sock.sendall(pkt)
        except (ConnectionError, OSError) as e:
            log.error(f"Error enviando paquete: {e}")


# -- Transferencia de archivos -----------------------------------------------

def send_file(receiver_id, filename, file_bytes, mime_type="application/octet-stream"):
    """Envia un archivo al destinatario via puerto de archivos (9001)."""
    if len(file_bytes) > MAX_FILE_SIZE:
        log.error(f"Archivo demasiado grande: {len(file_bytes)} bytes (max {MAX_FILE_SIZE})")
        return False

    transfer_id = uuid.uuid4().hex
    total_chunks = max(1, math.ceil(len(file_bytes) / CHUNK_SIZE))

    try:
        fsock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        fsock.connect((_host, FILE_PORT))
    except (ConnectionError, OSError) as e:
        log.error(f"No se pudo conectar al puerto de archivos {FILE_PORT}: {e}")
        return False

    try:
        auth_payload = json.dumps({
            "action": "login",
            "username": BOT_USERNAME,
            "password_hash": _password_hash,
            "device_type": "desktop"
        }, separators=(',', ':')).encode("utf-8")

        _send_on_sock(fsock, OP_AUTH_REQUEST, 0, auth_payload)
        result = read_packet(fsock)
        if result is None:
            log.error("Sin respuesta de auth en canal de archivos")
            return False
        opcode, _, _, _, payload = result
        if opcode != OP_AUTH_RESPONSE:
            log.error("Respuesta inesperada en canal de archivos")
            return False
        resp = json.loads(payload.decode("utf-8"))
        if not resp.get("ok"):
            log.error(f"Auth fallida en canal de archivos: {resp.get('error')}")
            return False

        metadata = json.dumps({
            "transfer_id": transfer_id,
            "filename": filename,
            "size": len(file_bytes),
            "mime_type": mime_type,
            "total_chunks": total_chunks
        }, separators=(',', ':')).encode("utf-8")
        _send_on_sock(fsock, OP_MSG_FILE, receiver_id, metadata)

        for i in range(total_chunks):
            start = i * CHUNK_SIZE
            end = min(start + CHUNK_SIZE, len(file_bytes))
            chunk_data = file_bytes[start:end]
            prefix = f"{transfer_id}|{i}|".encode("utf-8")
            _send_on_sock(fsock, OP_FILE_CHUNK, receiver_id, prefix + chunk_data)

        file_crc = zlib.crc32(file_bytes) & 0xFFFFFFFF
        complete_payload = json.dumps({
            "transfer_id": transfer_id,
            "checksum_crc32": file_crc
        }, separators=(',', ':')).encode("utf-8")
        _send_on_sock(fsock, OP_FILE_COMPLETE, receiver_id, complete_payload)

        log.info(f"Archivo '{filename}' enviado ({total_chunks} chunks, {len(file_bytes)} bytes)")
        return True

    except Exception as e:
        log.error(f"Error enviando archivo: {e}")
        return False
    finally:
        try:
            fsock.close()
        except Exception:
            pass


def _send_on_sock(sock, opcode, receiver_id, payload=b"", flags=0):
    """Envia un paquete en un socket especifico (sin usar el lock global)."""
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
    sock.sendall(body + struct.pack(">I", crc))


# -- Autenticacion -----------------------------------------------------------

def authenticate(host, port):
    global _sock, _my_user_id, _my_token, _password_hash

    _password_hash = hashlib.sha256(BOT_PASSWORD.encode()).hexdigest()
    auth_payload = json.dumps({
        "action": "login",
        "username": BOT_USERNAME,
        "password_hash": _password_hash,
        "device_type": "desktop"
    }, separators=(',', ':')).encode("utf-8")

    send_packet(_sock, OP_AUTH_REQUEST, 0, auth_payload)
    log.info(f"AUTH_REQUEST enviado como '{BOT_USERNAME}'")

    result = read_packet(_sock)
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
        log.warning(f"Login fallido: {error}. Intentando registrar...")

        _sock.close()
        _sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        _sock.connect((host, port))

        reg_payload = json.dumps({
            "action": "register",
            "username": BOT_USERNAME,
            "password_hash": _password_hash,
            "device_type": "desktop"
        }, separators=(',', ':')).encode("utf-8")
        send_packet(_sock, OP_AUTH_REQUEST, 0, reg_payload)

        result = read_packet(_sock)
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
    """Procesa un comando de texto y retorna la respuesta (o tupla para /pedir y /reporte)."""
    text = text.strip()

    if text.startswith("/"):
        return _dispatch_slash(sender_id, text)

    return _dispatch_keyword(sender_id, text)


def _dispatch_slash(sender_id, text):
    parts = text.split(maxsplit=1)
    cmd = parts[0].lower()
    args = parts[1] if len(parts) > 1 else ""

    try:
        if cmd == "/catalogo":
            return catalog.handle_catalogo()
        elif cmd == "/buscar":
            return catalog.handle_buscar(args)
        elif cmd == "/precio":
            return catalog.handle_precio(args)
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
        elif cmd == "/grupo_crear":
            return _cmd_grupo_crear(args)
        elif cmd == "/grupo_agregar":
            return _cmd_grupo_agregar(sender_id, args)
        elif cmd == "/grupo_salir":
            return _cmd_grupo_salir(sender_id, args)
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


def _dispatch_keyword(sender_id, text):
    """Detecta keywords sin prefijo / y mapea al comando correspondiente."""
    lower = text.lower().strip()

    if lower in ("catalogo", "productos", "ver catalogo"):
        return catalog.handle_catalogo()
    elif lower in ("ayuda", "help", "comandos"):
        return _help_text()
    elif lower in ("mis pedidos", "pedidos", "mis_pedidos"):
        return order_manager.handle_mis_pedidos(sender_id)

    if lower.startswith("buscar "):
        return catalog.handle_buscar(text[7:])
    elif lower.startswith("precio "):
        return catalog.handle_precio(text[7:])
    elif lower.startswith("pedir "):
        return order_manager.handle_pedir(sender_id, text[6:])
    elif lower.startswith("estado "):
        return order_manager.handle_estado(text[7:])
    elif lower.startswith("reporte"):
        args = text[7:].strip() if len(text) > 7 else ""
        return metrics.handle_reporte(args)

    log.info(f"Mensaje de cliente (user {sender_id}): {text}")
    return (
        "Hola! Soy el bot de ventas de Dog Messenger.\n"
        "Escribe /ayuda o 'ayuda' para ver los comandos disponibles."
    )


# -- Comandos de grupo -------------------------------------------------------

def _cmd_grupo_crear(args):
    name = args.strip()
    if not name:
        return "Uso: /grupo_crear <nombre del grupo>"
    payload = json.dumps({"name": name}, separators=(',', ':')).encode("utf-8")
    send_packet(_sock, OP_GROUP_CREATE, 0, payload)
    return f"Solicitud de creacion de grupo '{name}' enviada."


def _cmd_grupo_agregar(sender_id, args):
    parts = args.strip().split()
    if len(parts) < 2:
        return "Uso: /grupo_agregar <group_id> <user_id>"
    try:
        group_id = int(parts[0])
        target_uid = int(parts[1])
    except ValueError:
        return "group_id y user_id deben ser numeros."
    payload = json.dumps({
        "group_id": group_id,
        "target_user_id": target_uid
    }, separators=(',', ':')).encode("utf-8")
    send_packet(_sock, OP_GROUP_JOIN, 0, payload)
    return f"Solicitud para agregar usuario {target_uid} al grupo {group_id} enviada."


def _cmd_grupo_salir(sender_id, args):
    gid = args.strip()
    if not gid:
        return "Uso: /grupo_salir <group_id>"
    try:
        group_id = int(gid)
    except ValueError:
        return "group_id debe ser un numero."
    payload = json.dumps({"group_id": group_id}, separators=(',', ':')).encode("utf-8")
    send_packet(_sock, OP_GROUP_LEAVE, 0, payload)
    return f"Solicitud para salir del grupo {group_id} enviada."


def _help_text():
    return (
        "=== COMANDOS DISPONIBLES ===\n"
        "\n"
        "  /catalogo                       — Ver todos los productos\n"
        "  /buscar <texto>                 — Buscar productos\n"
        "  /precio <id o nombre>           — Ver precio de un producto\n"
        "  /pedir <id> [cant]              — Hacer un pedido\n"
        "  /mis_pedidos                    — Ver tus pedidos\n"
        "  /estado <ORD-XXXX>              — Detalle de un pedido\n"
        "  /reporte [diario|semanal|mensual] — Reporte de ventas\n"
        "  /confirmar <ORD-XXXX>           — Confirmar pedido\n"
        "  /entregar <ORD-XXXX>            — Marcar como entregado\n"
        "  /grupo_crear <nombre>           — Crear un grupo\n"
        "  /grupo_agregar <gid> <uid>      — Agregar miembro a grupo\n"
        "  /grupo_salir <gid>              — Salir de un grupo\n"
        "  /ayuda                          — Mostrar esta ayuda\n"
        "\n"
        "Tambien puedes escribir sin /: catalogo, buscar X, precio X, pedir X, ayuda\n"
    )


# -- Hilos de trabajo --------------------------------------------------------

def reader_thread(sock):
    """Hilo lector: recibe paquetes y los encola."""
    global _running, _reconnect

    while _running:
        result = read_packet(sock)
        if result is None:
            log.warning("Conexion perdida con el servidor")
            _reconnect = True
            _running = False
            break
        _msg_queue.put(result)


def worker_thread(sock):
    """Hilo worker: despacha mensajes de la cola."""
    global _running

    while _running:
        try:
            result = _msg_queue.get(timeout=1)
        except queue_mod.Empty:
            continue

        opcode, sender_id, receiver_id, flags, payload = result

        if opcode == OP_PING:
            pass

        elif opcode in (OP_MSG_TEXT, OP_GROUP_MSG):
            _handle_text_message(sock, opcode, sender_id, flags, payload)

        elif opcode == OP_SALES_QUERY:
            try:
                text = payload.decode("utf-8")
                response = dispatch_command(sender_id, text)
                text_resp, _ = _extract_text(response)
                if text_resp:
                    send_packet(sock, OP_SALES_RESPONSE, sender_id, text_resp.encode("utf-8"))
            except Exception as e:
                log.error(f"Error en SALES_QUERY: {e}")

        elif opcode == OP_MSG_ACK:
            try:
                ack = json.loads(payload.decode("utf-8"))
                if ack.get("action") == "added_to_group":
                    log.info(f"Agregado al grupo {ack.get('group_id')} por usuario {ack.get('by')}")
                elif ack.get("group_id"):
                    log.info(f"Grupo creado con id={ack.get('group_id')}")
            except Exception:
                log.debug(f"ACK recibido: {payload}")

        else:
            log.debug(f"Opcode no manejado: {opcode:#04x}")


def _handle_text_message(sock, opcode, sender_id, flags, payload):
    """Procesa un mensaje de texto (individual o de grupo)."""
    if flags & FLAG_ENCRYPTED:
        log.debug(f"Mensaje cifrado de user {sender_id}, ignorado")
        return

    try:
        text = payload.decode("utf-8")
    except UnicodeDecodeError:
        log.warning(f"Payload no es UTF-8 de user {sender_id}")
        return

    is_group = opcode == OP_GROUP_MSG
    source = f"grupo (user {sender_id})" if is_group else f"user {sender_id}"
    log.info(f"Mensaje de {source}: {text}")

    response = dispatch_command(sender_id, text)
    text_resp, order_id = _extract_text(response)

    if text_resp:
        if is_group:
            send_packet(sock, OP_GROUP_MSG, sender_id, text_resp.encode("utf-8"), FLAG_IS_GROUP)
        else:
            send_packet(sock, OP_MSG_TEXT, sender_id, text_resp.encode("utf-8"))
        log.info(f"Respuesta enviada a {source}")

    # Enviar PDF si corresponde
    if order_id:
        _send_pdf_receipt(sender_id, order_id)

    # Para reportes que generan PDF
    if isinstance(response, tuple) and len(response) == 2:
        _, pdf_result = response
        if pdf_result and isinstance(pdf_result, tuple):
            filename, pdf_bytes = pdf_result
            threading.Thread(
                target=send_file,
                args=(sender_id, filename, pdf_bytes, "application/pdf"),
                daemon=True
            ).start()


def _extract_text(response):
    """Extrae texto y order_id de la respuesta del dispatch."""
    if isinstance(response, tuple):
        if len(response) == 2:
            first, second = response
            if isinstance(first, str):
                return first, second
            return str(first) if first else None, second
        return str(response[0]) if response else None, None
    return response, None


def _send_pdf_receipt(receiver_id, order_id):
    """Genera y envia comprobante PDF en un hilo separado."""
    def _do_send():
        pdf_result = receipt_generator.generate_receipt_pdf(order_id)
        if pdf_result:
            filename, pdf_bytes = pdf_result
            send_file(receiver_id, filename, pdf_bytes, "application/pdf")

    threading.Thread(target=_do_send, daemon=True).start()


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
    global _running, _reconnect, _sock, _host, _port

    # Parsear argumentos
    _host = SERVER_HOST
    _port = SERVER_PORT
    for i, arg in enumerate(sys.argv[1:], 1):
        if arg in ("--host", "--server") and i < len(sys.argv) - 1:
            _host = sys.argv[i + 1]
        elif arg == "--port" and i < len(sys.argv) - 1:
            _port = int(sys.argv[i + 1])

    db.init_db()
    log.info("Base de datos de ventas inicializada")

    backoff = 1
    max_backoff = 30

    while True:
        _running = True
        _reconnect = False

        log.info(f"Conectando a {_host}:{_port}...")
        try:
            _sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            _sock.connect((_host, _port))
            log.info("Conectado al servidor")
        except (ConnectionError, OSError) as e:
            log.error(f"No se pudo conectar: {e}")
            log.info(f"Reintentando en {backoff}s...")
            time.sleep(backoff)
            backoff = min(backoff * 2, max_backoff)
            continue

        if not authenticate(_host, _port):
            log.error("No se pudo autenticar. Reintentando...")
            try:
                _sock.close()
            except Exception:
                pass
            time.sleep(backoff)
            backoff = min(backoff * 2, max_backoff)
            continue

        backoff = 1

        # Vaciar cola de mensajes pendientes de conexion anterior
        while not _msg_queue.empty():
            try:
                _msg_queue.get_nowait()
            except queue_mod.Empty:
                break

        t_reader = threading.Thread(target=reader_thread, args=(_sock,), daemon=True, name="reader")
        t_worker = threading.Thread(target=worker_thread, args=(_sock,), daemon=True, name="worker")
        t_ping = threading.Thread(target=ping_thread, args=(_sock,), daemon=True, name="ping")

        t_reader.start()
        t_worker.start()
        t_ping.start()

        log.info("Nodo de ventas activo. Esperando comandos...")

        try:
            while _running:
                time.sleep(1)
        except KeyboardInterrupt:
            log.info("Deteniendo nodo de ventas...")
            _running = False
            try:
                send_packet(_sock, OP_DISCONNECT, 0)
            except Exception:
                pass
            _sock.close()
            log.info("Nodo de ventas detenido.")
            return

        try:
            _sock.close()
        except Exception:
            pass

        if _reconnect:
            log.info(f"Reconectando en {backoff}s...")
            time.sleep(backoff)
            backoff = min(backoff * 2, max_backoff)
        else:
            break

    log.info("Nodo de ventas finalizado.")


if __name__ == "__main__":
    main()
