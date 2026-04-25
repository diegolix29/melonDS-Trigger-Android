package me.magnum.melonds.ui.multiplayer

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.magnum.melonds.R
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

data class LANGameInfo(
    val hostName: String,
    val gameName: String,
    val playerCount: Int,
    val maxPlayers: Int,
    val hostAddress: String
)

class LANJoinDialog : DialogFragment() {

    interface OnLANJoinListener {
        fun onLANJoin(playerName: String, gameInfo: LANGameInfo, password: String?)
        fun onLANDirectConnect(playerName: String, hostAddress: String, password: String?)
    }

    private var listener: OnLANJoinListener? = null
    private lateinit var playerNameEditText: EditText
    private lateinit var gamesRecyclerView: RecyclerView
    private lateinit var directConnectEditText: EditText
    private lateinit var directConnectButton: Button
    private lateinit var joinGameButton: Button
    private lateinit var gamesAdapter: GamesAdapter
    private lateinit var passwordEditText: EditText
    private lateinit var passwordCheckBox: CheckBox
    private val handler = Handler(Looper.getMainLooper())
    private var discoveryRunnable: Runnable? = null
    private var selectedGame: LANGameInfo? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (parentFragment is OnLANJoinListener) {
            listener = parentFragment as OnLANJoinListener
        } else if (context is OnLANJoinListener) {
            listener = context
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_lan_join, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get preferences for player name, timeout, and port
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val savedPlayerName = prefs.getString("lan_player_name", "") ?: ""
        val recvTimeout = prefs.getInt("mp_recv_timeout", 1)

        // Set LAN mode with timeout from preferences, then start discovery
        LANManager.setMode(recvTimeout)
        LANManager.startDiscovery()

        playerNameEditText = view.findViewById(R.id.edit_text_player_name)
        // Pre-fill player name from saved preferences
        playerNameEditText.setText(savedPlayerName)
        gamesRecyclerView = view.findViewById(R.id.recycler_view_games)
        directConnectEditText = view.findViewById(R.id.edit_text_direct_connect)
        directConnectButton = view.findViewById(R.id.button_direct_connect)
        joinGameButton = view.findViewById(R.id.button_join_game)
        passwordEditText = view.findViewById(R.id.edit_text_password)
        passwordCheckBox = view.findViewById(R.id.checkbox_password)
        val cancelButton = view.findViewById<Button>(R.id.button_cancel)

        // Setup games list
        gamesAdapter = GamesAdapter { gameInfo ->
            selectedGame = gameInfo
            // Clear direct connect field when game is selected
            directConnectEditText.text.clear()
            // Update join button state
            updateJoinButtonState()
            
            // Show selection feedback
            Toast.makeText(context, "Selected: ${gameInfo.hostName}", Toast.LENGTH_SHORT).show()
        }
        gamesRecyclerView.layoutManager = LinearLayoutManager(context)
        gamesRecyclerView.adapter = gamesAdapter

        // Password checkbox toggle
        passwordCheckBox.setOnCheckedChangeListener { _, isChecked ->
            passwordEditText.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                passwordEditText.text.clear()
            }
        }
        passwordEditText.visibility = View.GONE

        // Setup direct connect
        directConnectButton.setOnClickListener {
            val playerName = playerNameEditText.text.toString().trim()
            val hostAddress = directConnectEditText.text.toString().trim()
            val password = if (passwordCheckBox.isChecked) passwordEditText.text.toString().trim() else null
            
            if (playerName.isEmpty()) {
                playerNameEditText.error = getString(R.string.error_player_name_required)
                return@setOnClickListener
            }
            
            if (hostAddress.isEmpty()) {
                directConnectEditText.error = getString(R.string.error_host_address_required)
                return@setOnClickListener
            }
            
            listener?.onLANDirectConnect(playerName, hostAddress, password)
            dismiss()
        }

        // Add text watcher to enable Join Game button when IP is entered
        directConnectEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateJoinButtonState()
            }
        })

        // Also update Join Game button when player name changes
        playerNameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateJoinButtonState()
            }
        })

        // Setup Join Game button
        joinGameButton.setOnClickListener {
            val playerName = playerNameEditText.text.toString().trim()
            val game = selectedGame
            val hostAddress = directConnectEditText.text.toString().trim()
            val password = if (passwordCheckBox.isChecked) passwordEditText.text.toString().trim() else null
            
            if (playerName.isEmpty()) {
                playerNameEditText.error = getString(R.string.error_player_name_required)
                return@setOnClickListener
            }
            
            if (game != null) {
                // Join selected game from list
                listener?.onLANJoin(playerName, game, password)
            } else if (hostAddress.isNotEmpty()) {
                // Direct connect using IP address
                listener?.onLANDirectConnect(playerName, hostAddress, password)
            } else {
                // No game selected and no IP entered
                return@setOnClickListener
            }
            
            dismiss()
        }

        // Initially disable join button until a game is selected
        joinGameButton.isEnabled = false

        cancelButton.setOnClickListener {
            // End discovery and switch back to local mode - matches desktop cancel flow
            LANManager.endDiscovery()
            LANManager.setLocalMode()
            dismiss()
        }

        // Start game discovery
        startGameDiscovery()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopGameDiscovery()
        listener = null
    }

    private var lastDiscoveredCount = -1

    private fun startGameDiscovery() {
        discoveryRunnable = object : Runnable {
            override fun run() {
                var games: List<LANGameInfo> = emptyList()
                try {
                    // Process LAN events (discovery packets + ENet)
                    // This is needed when no game is running since the core loop isn't calling Process()
                    LANManager.process()

                    // Get discovered games
                    games = getDiscoveredGames()
                    updateGamesList(games)
                } catch (e: Exception) {
                    updateGamesList(emptyList())
                } finally {
                    // ALWAYS reschedule - even if exceptions occur
                    // Process ENet events more frequently (50ms) for faster connection establishment
                    handler.postDelayed(this, 50)
                }

                // Show toast when discovery count changes (helps debug if timer is running)
                if (games.size != lastDiscoveredCount) {
                    lastDiscoveredCount = games.size
                    if (games.isNotEmpty()) {
                        Toast.makeText(context, "Found ${games.size} LAN room(s)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Start discovery immediately
        handler.post(discoveryRunnable!!)
    }

    private fun stopGameDiscovery() {
        discoveryRunnable?.let { handler.removeCallbacks(it) }
        discoveryRunnable = null
    }

    private fun updateGamesList(games: List<LANGameInfo>) {
        // Only update adapter if list actually changed to avoid rebinding during clicks
        if (gamesAdapter.currentGames != games) {
            gamesAdapter.updateGames(games)
        }
    }

    private fun updateJoinButtonState() {
        val hasPlayerName = playerNameEditText.text.toString().trim().isNotEmpty()
        val hasHostAddress = directConnectEditText.text.toString().trim().isNotEmpty()
        val hasSelectedGame = selectedGame != null
        
        // Enable join button if:
        // 1. Has player name AND either has selected game OR has host address
        joinGameButton.isEnabled = hasPlayerName && (hasSelectedGame || hasHostAddress)
    }

    private fun getDiscoveredGames(): List<LANGameInfo> {
        // Use real LAN discovery
        return try {
            LANManager.getAvailableRooms()
        } catch (e: Exception) {
            emptyList()
        }
    }

    class GamesAdapter(
        private val onGameClick: (LANGameInfo) -> Unit
    ) : RecyclerView.Adapter<GamesAdapter.GameViewHolder>() {

        private var games = listOf<LANGameInfo>()
        val currentGames: List<LANGameInfo> get() = games

        var selectedPosition = -1
            private set

        fun updateGames(newGames: List<LANGameInfo>) {
            games = newGames
            selectedPosition = -1
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_lan_game, parent, false)
            return GameViewHolder(view)
        }

        override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
            holder.bind(games[position], position)
        }

        override fun getItemCount(): Int = games.size

        inner class GameViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val gameNameTextView: TextView = itemView.findViewById(R.id.text_game_name)
            private val hostNameTextView: TextView = itemView.findViewById(R.id.text_host_name)
            private val playerCountTextView: TextView = itemView.findViewById(R.id.text_player_count)
            private val hostAddressTextView: TextView = itemView.findViewById(R.id.text_host_address)

            fun bind(game: LANGameInfo, position: Int) {
                gameNameTextView.text = game.gameName
                hostNameTextView.text = game.hostName
                playerCountTextView.text = "${game.playerCount}/${game.maxPlayers} players"
                hostAddressTextView.text = game.hostAddress

                // Visual selection highlight
                if (position == selectedPosition) {
                    itemView.setBackgroundColor(0xFFCCCCCC.toInt())
                } else {
                    val selectableBg = android.util.TypedValue()
                    itemView.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectableBg, true)
                    itemView.setBackgroundResource(selectableBg.resourceId)
                }
                
                // Set click listener on the root item view
                itemView.setOnClickListener {
                    val previousSelected = selectedPosition
                    selectedPosition = position
                    notifyItemChanged(previousSelected)
                    notifyItemChanged(position)
                    onGameClick(game)
                }
                itemView.isClickable = true
                itemView.isFocusable = true
            }
        }
    }

    companion object {
        fun newInstance(): LANJoinDialog {
            return LANJoinDialog()
        }
    }
}
