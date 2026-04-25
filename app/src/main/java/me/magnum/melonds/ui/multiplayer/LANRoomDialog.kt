package me.magnum.melonds.ui.multiplayer

import android.app.Dialog
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.magnum.melonds.R
import java.net.InetAddress

data class LANPlayerInfo(
    val playerName: String,
    val playerAddress: String,
    val isHost: Boolean = false,
    val isReady: Boolean = false
)

class LANRoomDialog : DialogFragment() {

    interface OnRoomLeaveListener {
        fun onRoomLeft()
    }

    private var roomLeaveListener: OnRoomLeaveListener? = null
    private lateinit var playersRecyclerView: RecyclerView
    private lateinit var playersAdapter: PlayersAdapter
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var roomInfoTextView: TextView
    private lateinit var ipAddressTextView: TextView
    private lateinit var chatInputEditText: EditText
    private lateinit var sendChatButton: Button
    private lateinit var toggleChatButton: Button
    private lateinit var chatContentLayout: View
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private val currentPlayers = mutableListOf<LANPlayerInfo>()
    private val chatMessages = mutableListOf<ChatMessage>()
    private var isHost = false
    private var isChatExpanded = true

    fun setRoomLeaveListener(listener: OnRoomLeaveListener) {
        roomLeaveListener = listener
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_lan_room, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playersRecyclerView = view.findViewById(R.id.recycler_view_players)
        chatRecyclerView = view.findViewById(R.id.recycler_view_chat)
        roomInfoTextView = view.findViewById(R.id.text_room_info)
        ipAddressTextView = view.findViewById(R.id.text_ip_address)
        chatInputEditText = view.findViewById(R.id.edit_text_chat_input)
        sendChatButton = view.findViewById(R.id.button_send_chat)
        toggleChatButton = view.findViewById(R.id.button_toggle_chat)
        chatContentLayout = view.findViewById(R.id.layout_chat_content)
        val leaveButton = view.findViewById<Button>(R.id.button_leave)

        // Setup players list
        playersAdapter = PlayersAdapter(currentPlayers)
        playersRecyclerView.layoutManager = LinearLayoutManager(context)
        playersRecyclerView.adapter = playersAdapter

        // Setup chat list
        chatAdapter = ChatAdapter(chatMessages)
        chatRecyclerView.layoutManager = LinearLayoutManager(context)
        chatRecyclerView.adapter = chatAdapter

        // Send chat button
        sendChatButton.setOnClickListener {
            val message = chatInputEditText.text.toString().trim()
            if (message.isNotEmpty()) {
                LANManager.sendChatMessage(message)
                chatInputEditText.text.clear()
            }
        }

        // Toggle chat expand/collapse
        toggleChatButton.setOnClickListener {
            isChatExpanded = !isChatExpanded
            chatContentLayout.visibility = if (isChatExpanded) View.VISIBLE else View.GONE
            toggleChatButton.text = if (isChatExpanded) "▼" else "▲"
        }

        leaveButton.setOnClickListener {
            LANManager.leaveRoom()
            roomLeaveListener?.onRoomLeft()
            dismiss()
        }

        // Check if host
        isHost = LANManager.isHost()

        // Process LAN events immediately to start discovery broadcast
        LANManager.process()

        // Start periodic updates
        startUpdates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopUpdates()
    }

    private fun startUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                // Process LAN events (discovery broadcast for host, ENet for both)
                // Process more frequently for better discovery responsiveness
                LANManager.process()
                updatePlayerList()
                updateRoomInfo()
                updateChat()
                handler.postDelayed(this, 500) // Update every 500ms for better responsiveness
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun stopUpdates() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }

    private fun updatePlayerList() {
        try {
            val playerList = LANManager.getPlayerListRaw()
            if (playerList != null) {
                currentPlayers.clear()
                for (entry in playerList) {
                    val parts = entry.split("|")
                    if (parts.size >= 5) {
                        val name = parts[0]
                        val status = parts[1]
                        val id = parts[2].toIntOrNull() ?: -1
                        val isLocalPlayer = parts[3] == "1"
                        val ping = parts[4].toIntOrNull() ?: 0

                        currentPlayers.add(LANPlayerInfo(
                            playerName = if (isLocalPlayer) "$name (You)" else name,
                            playerAddress = "ID: $id | $status",
                            isHost = status == "Host",
                            isReady = status == "Host" || status == "Client"
                        ))
                    }
                }
                playersAdapter.updatePlayers(currentPlayers)
            }
        } catch (e: Exception) {
            // Ignore errors during update
        }
    }

    private fun updateRoomInfo() {
        try {
            val roomInfo = LANManager.getRoomInfo()
            if (roomInfo != null) {
                roomInfoTextView.text = "Room: ${roomInfo.roomName} [${roomInfo.roomCode}]\n" +
                        "Game: ${roomInfo.gameName}\n" +
                        "Players: ${roomInfo.numPlayers}/${roomInfo.maxPlayers}" +
                        if (roomInfo.hasPassword) " (Password Protected)" else ""
                
                // Show IP address for sharing
                if (isHost) {
                    ipAddressTextView.text = "Your IP: ${getLocalIpAddress()}"
                } else {
                    ipAddressTextView.text = ""
                }
            } else {
                roomInfoTextView.text = "Room info unavailable"
                ipAddressTextView.text = ""
            }
        } catch (e: Exception) {
            roomInfoTextView.text = "Room info unavailable"
            ipAddressTextView.text = ""
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = context?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipAddress = wifiInfo?.ipAddress ?: 0
            if (ipAddress != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    (ipAddress and 0xff),
                    (ipAddress shr 8 and 0xff),
                    (ipAddress shr 16 and 0xff),
                    (ipAddress shr 24 and 0xff)
                )
            }
        } catch (e: Exception) {
            // Ignore
        }
        return "Unknown"
    }

    private fun updateChat() {
        try {
            val messages = LANManager.getChatMessages()
            if (messages.isNotEmpty()) {
                // Only add new messages
                val currentSize = chatMessages.size
                if (messages.size > currentSize) {
                    chatMessages.clear()
                    chatMessages.addAll(messages)
                    chatAdapter.notifyDataSetChanged()
                    // Scroll to bottom
                    chatRecyclerView.scrollToPosition(chatMessages.size - 1)
                }
            }
        } catch (e: Exception) {
            // Ignore errors during update
        }
    }

    companion object {
        fun newInstance(): LANRoomDialog {
            return LANRoomDialog()
        }
    }
}

class ChatAdapter(private val messages: List<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageTextView: TextView = itemView.findViewById(R.id.text_chat_message)
        private val senderTextView: TextView = itemView.findViewById(R.id.text_chat_sender)

        fun bind(message: ChatMessage) {
            senderTextView.text = "Player ${message.senderID}"
            messageTextView.text = message.message
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size
}

class PlayersAdapter(private var players: List<LANPlayerInfo>) : RecyclerView.Adapter<PlayersAdapter.PlayerViewHolder>() {

    class PlayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val playerNameTextView: TextView = itemView.findViewById(R.id.text_player_name)
        private val playerAddressTextView: TextView = itemView.findViewById(R.id.text_player_address)
        private val hostBadgeTextView: TextView = itemView.findViewById(R.id.text_host_badge)
        private val readyStatusTextView: TextView = itemView.findViewById(R.id.text_ready_status)

        fun bind(player: LANPlayerInfo) {
            playerNameTextView.text = player.playerName
            playerAddressTextView.text = player.playerAddress
            
            // Show host badge
            hostBadgeTextView.visibility = if (player.isHost) View.VISIBLE else View.GONE
            
            // Show ready status
            readyStatusTextView.text = if (player.isReady) "Ready" else "Not Ready"
            readyStatusTextView.setTextColor(
                itemView.context.getColor(
                    if (player.isReady) android.R.color.holo_green_dark 
                    else android.R.color.holo_orange_dark
                )
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_player, parent, false)
        return PlayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        holder.bind(players[position])
    }

    override fun getItemCount(): Int = players.size

    fun updatePlayers(newPlayers: List<LANPlayerInfo>) {
        players = newPlayers
        notifyDataSetChanged()
    }
}
