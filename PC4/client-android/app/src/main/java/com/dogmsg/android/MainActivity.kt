package com.dogmsg.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.*

/**
 * MainActivity (T-B9): pantalla de login y, tras autenticar, lista de
 * conversaciones. Se enlaza al [SocketService] para obtener el [ChatEngine]
 * compartido (mismo engine sobrevive a cambios de pantalla/orientacion porque
 * vive en el servicio en primer plano).
 */
class MainActivity : AppCompatActivity(), ChatEngine.UiCallbacks {

    private lateinit var flipper: ViewFlipper
    private lateinit var inputUser: EditText
    private lateinit var inputPass: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnRegister: Button
    private lateinit var loginStatus: TextView

    private lateinit var listChats: RecyclerView
    private lateinit var fabNewChat: FloatingActionButton
    private lateinit var btnShowQr: Button
    private lateinit var btnScanQr: Button
    private lateinit var btnNewGroup: Button

    private val adapter = ChatListAdapter { conv -> openChat(conv) }

    private var engine: ChatEngine? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as SocketService.LocalBinder
            engine = local.getEngine()
            engine?.setUi(this@MainActivity)
            bound = true
            if (engine?.isConnected() == true && engine?.myUserId() != 0L) {
                showChats()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        flipper = findViewById(R.id.flipper)
        inputUser = findViewById(R.id.inputUser)
        inputPass = findViewById(R.id.inputPass)
        btnLogin = findViewById(R.id.btnLogin)
        btnRegister = findViewById(R.id.btnRegister)
        loginStatus = findViewById(R.id.loginStatus)

        listChats = findViewById(R.id.listChats)
        fabNewChat = findViewById(R.id.fabNewChat)
        btnShowQr = findViewById(R.id.btnShowQr)
        btnScanQr = findViewById(R.id.btnScanQr)
        btnNewGroup = findViewById(R.id.btnNewGroup)

        listChats.layoutManager = LinearLayoutManager(this)
        listChats.adapter = adapter

        btnLogin.setOnClickListener { doAuth(register = false) }
        btnRegister.setOnClickListener { doAuth(register = true) }

        fabNewChat.setOnClickListener { promptNewChat() }
        btnNewGroup.setOnClickListener { promptNewGroup() }
        btnShowQr.setOnClickListener {
            startActivity(Intent(this, QRScanActivity::class.java)
                .putExtra(QRScanActivity.EXTRA_MODE, QRScanActivity.MODE_SHOW))
        }
        btnScanQr.setOnClickListener {
            startActivity(Intent(this, QRScanActivity::class.java)
                .putExtra(QRScanActivity.EXTRA_MODE, QRScanActivity.MODE_SCAN))
        }

        val svcIntent = Intent(this, SocketService::class.java)
        startService(svcIntent)
        bindService(svcIntent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (bound) {
            engine?.setUi(null)
            unbindService(connection)
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        engine?.setUi(this)
        refreshChatList()
    }

    // ----------------- Acciones de UI -----------------

    private fun doAuth(register: Boolean) {
        val user = inputUser.text.toString().trim()
        val pass = inputPass.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            loginStatus.text = "Completa usuario y contrasena"
            return
        }
        loginStatus.text = getString(R.string.status_connecting)
        engine?.authenticate(user, pass, register)
    }

    private fun showChats() {
        flipper.displayedChild = 1
        refreshChatList()
    }

    private fun refreshChatList() {
        adapter.notifyDataSetChanged()
    }

    private fun promptNewChat() {
        val input = EditText(this)
        input.hint = getString(R.string.hint_peer_id)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_new_chat)
            .setView(input)
            .setPositiveButton(R.string.btn_send) { _, _ ->
                val peerId = input.text.toString().trim().toLongOrNull()
                if (peerId != null) {
                    openChat(ChatListAdapter.Conversation(peerId, false, "Usuario $peerId", "", 0))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Dialogo de creacion de grupo: nombre + lista de IDs numericos de
     * miembros (no existe lookup username->id, ver ChatEngine.addToGroup).
     * Se construye programaticamente porque la lista de miembros agregados
     * es de tamano variable.
     */
    private fun promptNewGroup() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val nameInput = EditText(this).apply { hint = getString(R.string.hint_group_name) }
        container.addView(nameInput)

        val memberRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val memberInput = EditText(this).apply {
            hint = getString(R.string.hint_member_id)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = Button(this).apply { text = getString(R.string.btn_add_member) }
        memberRow.addView(memberInput)
        memberRow.addView(addBtn)
        container.addView(memberRow)

        val membersListView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(membersListView)

        val memberIds = mutableListOf<Long>()
        addBtn.setOnClickListener {
            val id = memberInput.text.toString().trim().toLongOrNull()
            if (id == null) {
                Toast.makeText(this, "El ID de usuario debe ser numerico.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!memberIds.contains(id)) {
                memberIds.add(id)
                val row = TextView(this).apply { text = "\u2022 Usuario $id" }
                membersListView.addView(row)
            }
            memberInput.setText("")
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.btn_new_group)
            .setView(container)
            .setPositiveButton(R.string.btn_create) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    engine?.createGroup(name, memberIds.toList())
                    Toast.makeText(this, "Grupo '$name' solicitado (${memberIds.size} miembros).", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openChat(conv: ChatListAdapter.Conversation) {
        startActivity(Intent(this, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_PEER_ID, conv.peerId)
            .putExtra(ChatActivity.EXTRA_IS_GROUP, conv.isGroup)
            .putExtra(ChatActivity.EXTRA_NAME, conv.name))
    }

    // ----------------- ChatEngine.UiCallbacks -----------------

    override fun onConnectionState(connected: Boolean, detail: String) {
        runOnUiThread {
            loginStatus.text = if (connected) getString(R.string.status_connected) else detail
        }
    }

    override fun onAuthResult(ok: Boolean, userId: Long, tokenOrError: String) {
        runOnUiThread {
            if (ok) {
                showChats()
                // Arranca el receptor de archivos solo tras login exitoso, ya
                // que necesita credenciales para autenticar su propia conexion
                // al puerto 9001 (ver ChatEngine.buildFileChannelAuthPayload).
                engine?.startFileChannel()
            } else {
                loginStatus.text = tokenOrError
            }
        }
    }

    override fun onTextMessage(peerId: Long, isGroup: Boolean, senderId: Long, text: String, timestamp: Long) {
        runOnUiThread {
            adapter.upsert(peerId, isGroup, if (isGroup) "Grupo $peerId" else "Usuario $peerId", text, timestamp)
        }
    }

    override fun onAck(sequence: Long) { /* manejado a nivel de ChatActivity para marcar entregado */ }

    override fun onQrToken(token: String, expiresInSeconds: Int) { /* manejado en QRScanActivity */ }

    override fun onQrValidated(ok: Boolean, message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override fun onHistorySynced(count: Int) {
        runOnUiThread { refreshChatList() }
    }

    override fun onAddedToGroup(groupId: Long, byUserId: Long, groupName: String) {
        runOnUiThread {
            val title = groupName.ifBlank { "Grupo $groupId" }
            adapter.upsert(groupId, true, title, "Fuiste agregado por usuario $byUserId", System.currentTimeMillis())
            Toast.makeText(this, "Te agregaron al grupo '$title'.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onGroupCreated(groupId: Long, name: String) {
        runOnUiThread {
            adapter.upsert(groupId, true, name, "Grupo creado", System.currentTimeMillis())
            Toast.makeText(this, "Grupo '$name' creado con ID $groupId.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onFileReceived(senderId: Long, receiverId: Long, isImage: Boolean, savedFile: java.io.File) {
        runOnUiThread {
            val preview = if (isImage) "\uD83D\uDDBC Imagen" else "\uD83D\uDCCE ${savedFile.name}"
            adapter.upsert(senderId, false, "Usuario $senderId", preview, System.currentTimeMillis())
        }
    }

    override fun onSystem(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
}

/**
 * Adaptador simple para la lista de conversaciones. Mantiene un mapa
 * peerId+isGroup -> Conversation, ordenado por ultimo mensaje.
 */
class ChatListAdapter(private val onClick: (Conversation) -> Unit) :
    RecyclerView.Adapter<ChatListAdapter.VH>() {

    data class Conversation(
        val peerId: Long,
        val isGroup: Boolean,
        val name: String,
        var lastMessage: String,
        var lastTimestamp: Long
    )

    private val items = LinkedHashMap<String, Conversation>()
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun upsert(peerId: Long, isGroup: Boolean, name: String, lastMessage: String, ts: Long) {
        val key = "$peerId:$isGroup"
        val existing = items[key]
        if (existing != null) {
            existing.lastMessage = lastMessage
            existing.lastTimestamp = ts
        } else {
            items[key] = Conversation(peerId, isGroup, name, lastMessage, ts)
        }
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.avatarLetter)
        val name: TextView = view.findViewById(R.id.chatName)
        val preview: TextView = view.findViewById(R.id.chatPreview)
        val time: TextView = view.findViewById(R.id.chatTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val conv = items.values.sortedByDescending { it.lastTimestamp }[position]
        holder.avatar.text = conv.name.firstOrNull()?.uppercase() ?: "?"
        holder.name.text = conv.name
        holder.preview.text = conv.lastMessage
        holder.time.text = if (conv.lastTimestamp > 0) fmt.format(Date(conv.lastTimestamp)) else ""
        holder.itemView.setOnClickListener { onClick(conv) }
    }

    override fun getItemCount(): Int = items.size
}