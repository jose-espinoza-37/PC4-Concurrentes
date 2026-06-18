package com.dogmsg.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * ChatActivity (T-B9): conversacion 1-a-1 o de grupo con un peerId/groupId fijo.
 * Carga el historial local desde [LocalCache] al abrir y se suscribe al
 * [ChatEngine] para mensajes nuevos mientras esta en foreground.
 */
class ChatActivity : AppCompatActivity(), ChatEngine.UiCallbacks {

    companion object {
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_IS_GROUP = "is_group"
        const val EXTRA_NAME = "name"
    }

    private var peerId: Long = 0
    private var isGroup: Boolean = false
    private var myUserId: Long = 0

    private lateinit var listMessages: RecyclerView
    private lateinit var inputMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnAttach: ImageButton
    private val adapter = MessageAdapter { row -> openOrShareFile(row) }

    private var engine: ChatEngine? = null
    private var bound = false

    private val filePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) sendFile(uri)
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as SocketService.LocalBinder
            engine = local.getEngine()
            engine?.setUi(this@ChatActivity)
            bound = true
            myUserId = engine?.myUserId() ?: 0
            loadHistory()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        peerId = intent.getLongExtra(EXTRA_PEER_ID, 0)
        isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "Chat"

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = name
        toolbar.setNavigationOnClickListener { finish() }

        listMessages = findViewById(R.id.listMessages)
        inputMessage = findViewById(R.id.inputMessage)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)

        val lm = LinearLayoutManager(this)
        lm.stackFromEnd = true
        listMessages.layoutManager = lm
        listMessages.adapter = adapter

        btnSend.setOnClickListener { sendCurrentText() }
        btnAttach.setOnClickListener { filePicker.launch("*/*") }

        val svcIntent = Intent(this, SocketService::class.java)
        startService(svcIntent)
        bindService(svcIntent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        engine?.setUi(this)
    }

    override fun onDestroy() {
        if (bound) {
            engine?.setUi(null)
            unbindService(connection)
        }
        super.onDestroy()
    }

    private fun loadHistory() {
        val recent = engine?.recentMessages(peerId, isGroup, 100) ?: emptyList()
        adapter.setItems(recent.map {
            MessageAdapter.Row(it.senderId == myUserId, it.text ?: "", it.timestamp)
        })
        listMessages.scrollToPosition(maxOf(0, adapter.itemCount - 1))
    }

    private fun sendCurrentText() {
        val text = inputMessage.text.toString().trim()
        if (text.isEmpty()) return
        engine?.sendText(peerId, isGroup, text)
        adapter.addItem(MessageAdapter.Row(true, text, System.currentTimeMillis()))
        listMessages.scrollToPosition(adapter.itemCount - 1)
        inputMessage.setText("")
    }

    /**
     * Copia el contenido del Uri elegido a un archivo temporal real (FileChannel
     * trabaja con java.io.File, no con Uri de content://), detecta si es imagen
     * por mime_type, y lo envia por el canal de archivos (puerto 9001).
     */
    private fun sendFile(uri: Uri) {
        val resolver = contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val isImage = mime.startsWith("image/")
        val displayName = queryDisplayName(uri) ?: "archivo_${System.currentTimeMillis()}"

        val tempFile = File(cacheDir, displayName)
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: run {
                Toast.makeText(this, "No se pudo leer el archivo seleccionado.", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error leyendo archivo: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        val progressRow = MessageAdapter.Row(
            outgoing = true,
            text = (if (isImage) "\uD83D\uDDBC " else "\uD83D\uDCCE ") + displayName,
            timestamp = System.currentTimeMillis(),
            file = tempFile,
            isImage = isImage
        )
        adapter.addItem(progressRow)
        listMessages.scrollToPosition(adapter.itemCount - 1)

        Thread {
            try {
                engine?.sendFile(peerId, tempFile, isImage) { /* progreso opcional */ }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error enviando archivo: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
        }
        return name
    }

    /** Abre el archivo recibido/enviado con la app del sistema correspondiente. */
    private fun openOrShareFile(row: MessageAdapter.Row) {
        val file = row.file ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = contentResolver.getType(uri) ?: if (row.isImage) "image/*" else "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ----------------- ChatEngine.UiCallbacks -----------------

    override fun onConnectionState(connected: Boolean, detail: String) {
        runOnUiThread { if (!connected) Toast.makeText(this, detail, Toast.LENGTH_SHORT).show() }
    }

    override fun onAuthResult(ok: Boolean, userId: Long, tokenOrError: String) {
        if (ok) myUserId = userId
    }

    override fun onTextMessage(peerId: Long, isGroup: Boolean, senderId: Long, text: String, timestamp: Long) {
        if (peerId != this.peerId || isGroup != this.isGroup) return
        runOnUiThread {
            adapter.addItem(MessageAdapter.Row(senderId == myUserId, text, timestamp))
            listMessages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    override fun onAck(sequence: Long) { /* podria marcar el ultimo item como entregado */ }
    override fun onQrToken(token: String, expiresInSeconds: Int) {}
    override fun onQrValidated(ok: Boolean, message: String) {}
    override fun onHistorySynced(count: Int) {
        runOnUiThread { loadHistory() }
    }

    override fun onAddedToGroup(groupId: Long, byUserId: Long, groupName: String) {
        // Notificacion global; esta pantalla solo reacciona si es el grupo abierto.
        if (groupId == peerId && isGroup) {
            runOnUiThread { Toast.makeText(this, "Te agregaron a '$groupName'", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onGroupCreated(groupId: Long, name: String) { /* manejado en MainActivity */ }

    override fun onFileReceived(senderId: Long, receiverId: Long, isImage: Boolean, savedFile: File) {
        if (senderId != peerId || isGroup) return // solo si es de la conversacion abierta
        runOnUiThread {
            adapter.addItem(
                MessageAdapter.Row(
                    outgoing = false,
                    text = (if (isImage) "\uD83D\uDDBC " else "\uD83D\uDCCE ") + savedFile.name,
                    timestamp = System.currentTimeMillis(),
                    file = savedFile,
                    isImage = isImage
                )
            )
            listMessages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    override fun onSystem(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
}

/** Adaptador de burbujas de mensaje, reutilizando item_message.xml (in/out). */
class MessageAdapter(private val onFileClick: (Row) -> Unit) : RecyclerView.Adapter<MessageAdapter.VH>() {

    data class Row(
        val outgoing: Boolean,
        val text: String,
        val timestamp: Long,
        val file: File? = null,
        val isImage: Boolean = false
    )

    private val items = mutableListOf<Row>()
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setItems(rows: List<Row>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    fun addItem(row: Row) {
        items.add(row)
        notifyItemInserted(items.size - 1)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val wrapperIn: LinearLayout = view.findViewById(R.id.bubbleInWrapper)
        val wrapperOut: LinearLayout = view.findViewById(R.id.bubbleOutWrapper)
        val textIn: TextView = view.findViewById(R.id.textIn)
        val textOut: TextView = view.findViewById(R.id.textOut)
        val meta: TextView = view.findViewById(R.id.textMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.wrapperIn.visibility = if (row.outgoing) View.GONE else View.VISIBLE
        holder.wrapperOut.visibility = if (row.outgoing) View.VISIBLE else View.GONE
        if (row.outgoing) holder.textOut.text = row.text else holder.textIn.text = row.text
        holder.meta.text = fmt.format(Date(row.timestamp))

        // Si la fila tiene un archivo real adjunto (enviado o recibido), tocar
        // el texto abre/comparte el archivo con la app del sistema.
        if (row.file != null) {
            val target = if (row.outgoing) holder.textOut else holder.textIn
            target.setOnClickListener { onFileClick(row) }
        } else {
            holder.textOut.setOnClickListener(null)
            holder.textIn.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int = items.size
}