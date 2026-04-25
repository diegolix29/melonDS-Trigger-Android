package me.magnum.melonds.ui.multiplayer

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import me.magnum.melonds.R
import java.net.NetworkInterface
import java.util.Collections
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LANHostDialog : DialogFragment() {

    interface OnLANHostListener {
        fun onLANHost(playerName: String, numPlayers: Int, roomName: String, password: String?)
    }

    private var listener: OnLANHostListener? = null
    private lateinit var playerNameEditText: EditText
    private lateinit var roomNameEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var numPlayersPicker: NumberPicker
    private lateinit var ipAddressTextView: TextView
    private lateinit var passwordCheckBox: CheckBox
    private var isDefaultRoomName = true
    
    // Simplified implementation without ROM dependencies to avoid build errors

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (parentFragment is OnLANHostListener) {
            listener = parentFragment as OnLANHostListener
        } else if (context is OnLANHostListener) {
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
        return inflater.inflate(R.layout.dialog_lan_host, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get preferences for player name, timeout, and port
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val savedPlayerName = prefs.getString("lan_player_name", "") ?: ""
        val defaultMaxPlayers = prefs.getInt("lan_host_num_players", 16)
        val recvTimeout = prefs.getInt("mp_recv_timeout", 1)

        // Set LAN mode immediately with timeout from preferences
        LANManager.setMode(recvTimeout)

        playerNameEditText = view.findViewById(R.id.edit_text_player_name)
        roomNameEditText = view.findViewById(R.id.edit_text_room_name)
        passwordEditText = view.findViewById(R.id.edit_text_password)
        numPlayersPicker = view.findViewById(R.id.number_picker_num_players)
        ipAddressTextView = view.findViewById(R.id.text_ip_address)
        passwordCheckBox = view.findViewById(R.id.checkbox_password)
        val hostButton = view.findViewById<Button>(R.id.button_host)
        val cancelButton = view.findViewById<Button>(R.id.button_cancel)

        // Pre-fill player name from saved preferences
        playerNameEditText.setText(savedPlayerName)
        
        // Set default room name to current game title
        setDefaultRoomName()
        
        // Auto-clear room name on first focus, reset to default if empty and loses focus
        roomNameEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && isDefaultRoomName) {
                roomNameEditText.text.clear()
                isDefaultRoomName = false
            } else if (!hasFocus && roomNameEditText.text.toString().trim().isEmpty()) {
                setDefaultRoomName()
                isDefaultRoomName = true
            }
        }

        // Setup number picker with saved default
        numPlayersPicker.minValue = 2
        numPlayersPicker.maxValue = 16
        numPlayersPicker.value = defaultMaxPlayers

        // Display IP address
        displayLocalIpAddress()

        // Password checkbox toggle
        passwordCheckBox.setOnCheckedChangeListener { _, isChecked ->
            passwordEditText.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                passwordEditText.text.clear()
            }
        }
        passwordEditText.visibility = View.GONE

        hostButton.setOnClickListener {
            val playerName = playerNameEditText.text.toString().trim()
            val roomName = roomNameEditText.text.toString().trim()
            val password = if (passwordCheckBox.isChecked) passwordEditText.text.toString().trim() else null

            if (playerName.isEmpty()) {
                playerNameEditText.error = getString(R.string.error_player_name_required)
                return@setOnClickListener
            }

            if (roomName.isEmpty()) {
                roomNameEditText.error = "Room name is required"
                return@setOnClickListener
            }

            if (passwordCheckBox.isChecked && (password.isNullOrEmpty() || password.length < 4)) {
                passwordEditText.error = "Password must be at least 4 characters"
                return@setOnClickListener
            }

            listener?.onLANHost(playerName, numPlayersPicker.value, roomName, password)
            dismiss()
        }

        cancelButton.setOnClickListener {
            // Switch back to local mode on cancel (matches desktop cancel flow)
            LANManager.setLocalMode()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listener = null
    }

    private fun displayLocalIpAddress() {
        val ip = getLocalIpAddress()
        ipAddressTextView.text = ip
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr.isLoopbackAddress) continue
                    val hostAddr = addr.hostAddress
                    if (hostAddr != null && (hostAddr.indexOf(':') < 0)) {
                        // IPv4 address found
                        return hostAddr
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return "192.168.1.100"
    }

    private fun setDefaultRoomName() {
        // Simplified default room name without ROM dependency
        // TODO: Implement proper ROM detection when integration is ready
        roomNameEditText.setText("Multiplayer Room")
    }

    companion object {
        fun newInstance(): LANHostDialog {
            return LANHostDialog()
        }
    }
}
