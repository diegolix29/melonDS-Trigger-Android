package me.magnum.melonds.ui.settings.fragments

import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import me.magnum.melonds.ui.multiplayer.LANHostDialog
import me.magnum.melonds.ui.multiplayer.LANJoinDialog
import me.magnum.melonds.ui.multiplayer.LANGameInfo
import me.magnum.melonds.ui.multiplayer.LANRoomDialog
import me.magnum.melonds.ui.multiplayer.LANManager
import dagger.hilt.android.AndroidEntryPoint
import me.magnum.melonds.R
import me.magnum.melonds.ui.settings.PreferenceFragmentTitleProvider
import javax.inject.Inject
import androidx.preference.PreferenceManager

@AndroidEntryPoint
class MultiplayerPreferencesFragment : BasePreferenceFragment(), PreferenceFragmentTitleProvider, LANHostDialog.OnLANHostListener, LANJoinDialog.OnLANJoinListener, LANRoomDialog.OnRoomLeaveListener {

    @Inject
    lateinit var preferencesRepository: me.magnum.melonds.domain.repositories.SettingsRepository

    // Room status tracking
    private var isInRoom = false
    private var currentRoomName = ""
    private var currentHostAddress = ""
    private var isHost = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_multiplayer, rootKey)
        
        setupWifiSettings()
        setupMultiplayerSettings()
    }

    override fun getTitle(): String = getString(R.string.multiplayer)

    override fun onResume() {
        super.onResume()
        // Restore room status from actual LAN state when fragment is resumed
        if (LANManager.isInSession()) {
            if (LANManager.isHost()) {
                val ip = getLocalIpAddress()
                updateRoomStatus(
                    inRoom = true,
                    roomName = "My Room",
                    hostAddress = ip,
                    isHost = true
                )
            } else {
                // For clients, preserve the existing hostAddress (the host's IP, not our local IP)
                updateRoomStatus(
                    inRoom = true,
                    roomName = currentRoomName.ifEmpty { "Joined Room" },
                    hostAddress = currentHostAddress,
                    isHost = false
                )
            }
        }
    }

    private fun setupWifiSettings() {
        val wifiEnabledPreference = findPreference<SwitchPreference>("wifi_enabled")
        val networkModePreference = findPreference<ListPreference>("network_mode")
        val networkAdapterPreference = findPreference<ListPreference>("network_adapter")

        wifiEnabledPreference?.setOnPreferenceChangeListener { _, newValue ->
            val isEnabled = newValue as Boolean
            networkModePreference?.isEnabled = isEnabled
            networkAdapterPreference?.isEnabled = isEnabled && networkModePreference?.value == "direct"
            true
        }

        networkModePreference?.setOnPreferenceChangeListener { _, newValue ->
            val mode = newValue as String
            networkAdapterPreference?.isEnabled = wifiEnabledPreference?.isChecked == true && mode == "direct"
            true
        }

        // Set initial states
        val wifiEnabled = wifiEnabledPreference?.isChecked == true
        val networkMode = networkModePreference?.value
        networkModePreference?.isEnabled = wifiEnabled
        networkAdapterPreference?.isEnabled = wifiEnabled && networkMode == "direct"
    }

    private fun setupMultiplayerSettings() {
        val multiplayerInterfacePreference = findPreference<ListPreference>("multiplayer_interface")
        val multiplayerInfoPreference = findPreference<Preference>("multiplayer_info")
        val lanHostGamePreference = findPreference<Preference>("lan_host_game")
        val lanJoinGamePreference = findPreference<Preference>("lan_join_game")
        
        fun updateLanControlsVisibility(isLanSelected: Boolean) {
            lanHostGamePreference?.isVisible = isLanSelected
            lanJoinGamePreference?.isVisible = isLanSelected
        }
        
        multiplayerInterfacePreference?.setOnPreferenceChangeListener { _, newValue ->
            val selectedValue = newValue as String
            val selectedIndex = multiplayerInterfacePreference.findIndexOfValue(selectedValue)
            val isLanSelected = selectedIndex == 1
            
            updateLanControlsVisibility(isLanSelected)
            
            when (selectedIndex) {
                0 -> { // Local
                    multiplayerInfoPreference?.setSummary("Local multiplayer works automatically on this device.")
                    true
                }
                1 -> { // LAN
                    multiplayerInfoPreference?.setSummary("LAN multiplayer is working. Use Host/Join buttons to create or join LAN games.")
                    true
                }
                2 -> { // Netplay
                    multiplayerInfoPreference?.setSummary("Netplay is not implemented yet. This would allow hosting/joining games over the internet.")
                    true
                }
                3 -> { // Dummy
                    multiplayerInfoPreference?.setSummary("Dummy mode disables all multiplayer functionality.")
                    true
                }
                else -> true
            }
        }
        
        // Set up LAN host button
        lanHostGamePreference?.setOnPreferenceClickListener {
            if (isInRoom && isHost) {
                // Already hosting, show current room
                showRoomDialog(isHost = true, hostAddress = currentHostAddress)
            } else {
                // Not hosting, show host dialog
                val dialog = LANHostDialog.newInstance()
                dialog.show(childFragmentManager, "LANHostDialog")
            }
            true
        }
        
        // Set up LAN join button
        lanJoinGamePreference?.setOnPreferenceClickListener {
            val dialog = LANJoinDialog.newInstance()
            dialog.show(childFragmentManager, "LANJoinDialog")
            true
        }
        
        // Set up multiplayer info preference click handler
        multiplayerInfoPreference?.setOnPreferenceClickListener {
            if (isInRoom) {
                // Show room dialog when in a room
                showRoomDialog(isHost = isHost, hostAddress = currentHostAddress)
            }
            true
        }

        // Set initial visibility and info based on current selection
        multiplayerInterfacePreference?.let { pref ->
            val currentValue = pref.value
            val currentIndex = pref.findIndexOfValue(currentValue)
            val isLanSelected = currentIndex == 1
            
            updateLanControlsVisibility(isLanSelected)
            
            when (currentIndex) {
                0 -> multiplayerInfoPreference?.setSummary("Local multiplayer works automatically on this device.")
                1 -> multiplayerInfoPreference?.setSummary("LAN multiplayer is working. Use Host/Join buttons to create or join LAN games.")
                2 -> multiplayerInfoPreference?.setSummary("Netplay is not implemented yet. This would allow hosting/joining games over the internet.")
                3 -> multiplayerInfoPreference?.setSummary("Dummy mode disables all multiplayer functionality.")
            }
        }
    }

    override fun onLANHost(playerName: String, numPlayers: Int, roomName: String, password: String?) {
        Toast.makeText(requireContext(), "Starting host...", Toast.LENGTH_SHORT).show()

        // Save player name to preferences (matches desktop behavior)
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.edit().putString("lan_player_name", playerName).apply()

        // Start hosting (LAN mode already set by dialog)
        val success = LANManager.startHost(playerName, numPlayers, roomName, password)

        if (success) {
            // Show detailed success notification
            val ip = getLocalIpAddress()
            val passwordInfo = if (password != null) " (Password Protected)" else ""
            val message = "LAN room created successfully!\nRoom: $roomName$passwordInfo\nIP: $ip\nMax players: $numPlayers"
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

            // Update room status
            updateRoomStatus(
                inRoom = true,
                roomName = roomName,
                hostAddress = ip,
                isHost = true
            )

            // Show the room dialog with host status
            showRoomDialog(isHost = true, hostAddress = ip)
        } else {
            // Switch back to local mode on failure (matches desktop cancel flow)
            LANManager.setLocalMode()
            Toast.makeText(requireContext(), "Failed to create LAN room", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLANJoin(playerName: String, gameInfo: LANGameInfo, password: String?) {
        Toast.makeText(requireContext(), "Joining ${gameInfo.hostAddress}...", Toast.LENGTH_SHORT).show()

        // Save player name to preferences (matches desktop behavior)
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.edit().putString("lan_player_name", playerName).apply()

        // End discovery before connecting (matches desktop flow)
        LANManager.endDiscovery()

        // Join the room
        val success = LANManager.startClient(playerName, gameInfo.hostAddress, password)

        if (success) {
            // Show popup message
            Toast.makeText(requireContext(), "Joining ${gameInfo.hostName}'s room", Toast.LENGTH_SHORT).show()

            // Update room status
            updateRoomStatus(
                inRoom = true,
                roomName = gameInfo.hostName,
                hostAddress = gameInfo.hostAddress,
                isHost = false
            )

            // Show the room dialog with client status
            showRoomDialog(isHost = false, hostAddress = gameInfo.hostAddress)
        } else {
            // Switch back to local mode on failure (matches desktop cancel flow)
            LANManager.setLocalMode()
            Toast.makeText(requireContext(), "Failed to join LAN room", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLANDirectConnect(playerName: String, hostAddress: String, password: String?) {
        Toast.makeText(requireContext(), "Connecting to $hostAddress...", Toast.LENGTH_SHORT).show()

        // Save player name to preferences (matches desktop behavior)
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.edit().putString("lan_player_name", playerName).apply()

        // End discovery before connecting (matches desktop flow)
        LANManager.endDiscovery()

        // Direct connect to host
        val success = LANManager.startClient(playerName, hostAddress, password)

        if (success) {
            // Show popup message
            Toast.makeText(requireContext(), "Connecting to $hostAddress", Toast.LENGTH_SHORT).show()

            // Update room status
            updateRoomStatus(
                inRoom = true,
                roomName = "Direct Connect",
                hostAddress = hostAddress,
                isHost = false
            )

            // Show the room dialog with client status
            showRoomDialog(isHost = false, hostAddress = hostAddress)
        } else {
            // Switch back to local mode on failure (matches desktop cancel flow)
            LANManager.setLocalMode()
            Toast.makeText(requireContext(), "Failed to connect to $hostAddress", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRoomDialog(isHost: Boolean, hostAddress: String) {
        val roomDialog = LANRoomDialog.newInstance()
        roomDialog.setRoomLeaveListener(this)
        roomDialog.show(childFragmentManager, "LANRoomDialog")
    }

    override fun onRoomLeft() {
        // Update room status to unlock buttons
        updateRoomStatus(
            inRoom = false,
            roomName = "",
            hostAddress = "",
            isHost = false
        )
    }

    private fun updateRoomStatus(inRoom: Boolean, roomName: String, hostAddress: String, isHost: Boolean) {
        isInRoom = inRoom
        currentRoomName = roomName
        currentHostAddress = hostAddress
        this.isHost = isHost
        
        // Update multiplayer info preference to show room status
        val multiplayerInfoPreference = findPreference<Preference>("multiplayer_info")
        multiplayerInfoPreference?.let { pref ->
            if (isInRoom) {
                val role = if (isHost) "Hosting" else "Joined"
                val statusText = if (isHost) {
                    "Hosting LAN room: $roomName\nIP: $hostAddress\nTap to manage room"
                } else {
                    "Joined LAN room: $roomName\nHost: $hostAddress\nTap to view room"
                }
                pref.summary = statusText
            } else {
                // Reset to default info based on current interface selection
                val multiplayerInterfacePreference = findPreference<ListPreference>("multiplayer_interface")
                multiplayerInterfacePreference?.let { mpPref ->
                    val currentIndex = mpPref.findIndexOfValue(mpPref.value ?: "0")
                    when (currentIndex) {
                        0 -> pref.summary = "Local multiplayer works automatically on this device."
                        1 -> pref.summary = "LAN multiplayer is working. Use Host/Join buttons to create or join LAN games."
                        2 -> pref.summary = "Netplay is not implemented yet. This would allow hosting/joining games over the internet."
                        3 -> pref.summary = "Dummy mode disables all multiplayer functionality."
                        else -> pref.summary = "Select multiplayer interface type"
                    }
                }
            }
        }
        
        // Update LAN controls visibility based on room status
        val lanHostGamePreference = findPreference<Preference>("lan_host_game")
        val lanJoinGamePreference = findPreference<Preference>("lan_join_game")
        
        // Disable host button when in any room
        lanHostGamePreference?.isEnabled = !isInRoom
        
        // Disable join button when user is hosting (can't join own room)
        // But allow joining when user is just a client in another room
        lanJoinGamePreference?.isEnabled = !isInRoom || !isHost
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr.isLoopbackAddress) continue
                    val hostAddr = addr.hostAddress
                    if (hostAddr != null && (hostAddr.indexOf(':') < 0)) {
                        return hostAddr
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return "192.168.1.100"
    }
}
