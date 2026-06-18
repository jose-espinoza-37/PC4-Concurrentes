package com.dogmsg.android

import android.util.Log
import com.dogmsg.android.protocol.OpCode
import com.dogmsg.android.protocol.Packet
import com.dogmsg.android.protocol.PacketParser
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * T-B1 (lado Android): conexion TCP con el servidor usando SOLO sockets nativos
 * (java.net.Socket). Sin librerias externas de comunicacion.
 *
 * Es el espejo de SocketManager.java:
 *  - Hilo de lectura continua que entrega cada Packet al listener.
 *  - Envio thread-safe de paquetes.
 *  - Reconexion automatica con backoff exponencial.
 *  - PING keep-alive cada 30 s (el servidor cierra si no hay PING en 90 s).
 *
 * No toca la UI: el [SocketService] lo aloja y reenvia los eventos al hilo
 * principal.
 */
class SocketClient(
    val host: String,
    private val port: Int,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        fun onPacket(packet: Packet)
        fun onDisconnected(reason: String)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private val writeLock = Any()

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val sequence = AtomicLong(1)

    @Volatile var myUserId: Long = 0
        private set

    private var readerThread: Thread? = null
    private var keepAlive: ScheduledExecutorService? = null
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "socket-writer").apply { isDaemon = true }
    }

    fun setMyUserId(id: Long) { myUserId = id }
    fun isConnected(): Boolean = connected.get()

    /** Numero de secuencia incremental para correlacionar ACKs. */
    fun nextSequence(): Long = sequence.getAndIncrement()

    /** Arranca la conexion (no bloqueante): hilo de lectura + reconexion + PING. */
    fun start() {
        if (running.getAndSet(true)) return
        Log.d(TAG, "start() llamado. host=$host port=$port")
        readerThread = Thread(::connectionLoop, "socket-reader").apply {
            isDaemon = true
            start()
        }
        keepAlive = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "keep-alive").apply { isDaemon = true }
        }.also {
            it.scheduleAtFixedRate(
                ::sendPing,
                PING_INTERVAL_SEC.toLong(),
                PING_INTERVAL_SEC.toLong(),
                TimeUnit.SECONDS
            )
        }
    }

    private fun connectionLoop() {
        var backoff = 500L
        while (running.get()) {
            try {
                Log.d(TAG, "Intentando conectar a $host:$port ...")
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                socket = s
                out = s.getOutputStream()
                connected.set(true)
                backoff = 500L
                Log.d(TAG, "Conectado a $host:$port")
                listener.onConnected()

                val parser = PacketParser(s.getInputStream())
                var pkt = parser.readPacket()
                while (running.get() && pkt != null) {
                    listener.onPacket(pkt)
                    pkt = parser.readPacket()
                }
                handleDisconnect("conexion cerrada por el servidor")
            } catch (e: IOException) {
                Log.e(TAG, "Error conectando a $host:$port -> ${e.javaClass.simpleName}: ${e.message}", e)
                handleDisconnect(e.message)
            }

            if (!running.get()) break

            try {
                Thread.sleep(backoff)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun handleDisconnect(reason: String?) {
        if (connected.getAndSet(false)) {
            closeSocketQuietly()
            listener.onDisconnected(reason ?: "desconectado")
        }
    }

    /**
     * Envia un paquete ya construido. Thread-safe.
     *
     * FIX: la escritura real al socket se despacha a [writeExecutor] (hilo de
     * fondo dedicado) porque Android prohibe operaciones de red en el hilo
     * principal (NetworkOnMainThreadException). Las Activities llaman a esto
     * directamente desde callbacks de UI (botones), asi que no se puede asumir
     * que el llamador ya esta en un hilo de fondo. Se bloquea con get() para
     * conservar la firma sincrona actual (sendPacket devuelve seq de inmediato
     * a quien la llamo, una vez la escritura termino).
     */
    @Throws(IOException::class)
    fun send(packet: Packet) {
        val o = out ?: throw IOException("No conectado")
        if (!connected.get()) throw IOException("No conectado")
        val bytes = packet.toBytes()
        try {
            writeExecutor.submit<Unit> {
                synchronized(writeLock) {
                    o.write(bytes)
                    o.flush()
                }
            }.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause)
        }
    }

    /** Construye y envia un paquete con el id de usuario actual como remitente. */
    @Throws(IOException::class)
    fun sendPacket(op: OpCode, receiverId: Long, flags: Int, payload: ByteArray?): Long {
        val seq = nextSequence()
        val p = Packet.now(op, seq, myUserId, receiverId, flags, payload)
        send(p)
        return seq
    }

    private fun sendPing() {
        if (!connected.get()) return
        try {
            sendPacket(OpCode.PING, 0, 0, ByteArray(0))
        } catch (ignored: IOException) {
            // el connectionLoop detectara la caida
        }
    }

    /** Cierre limpio: envia DISCONNECT y para todos los hilos. */
    fun shutdown() {
        running.set(false)
        try {
            if (connected.get()) sendPacket(OpCode.DISCONNECT, 0, 0, ByteArray(0))
        } catch (ignored: IOException) {
        }
        keepAlive?.shutdownNow()
        writeExecutor.shutdownNow()
        closeSocketQuietly()
        readerThread?.interrupt()
    }

    private fun closeSocketQuietly() {
        try {
            socket?.close()
        } catch (ignored: IOException) {
        }
        socket = null
        out = null
    }

    companion object {
        private const val TAG = "SocketClient"
        private const val PING_INTERVAL_SEC = 30
        private const val MAX_BACKOFF_MS = 30_000L
    }
}