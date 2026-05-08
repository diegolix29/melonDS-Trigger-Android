package me.magnum.melonds.ui.multiplayer

data class RoomInfo(
    val roomCode: String,
    val roomName: String,
    val gameName: String,
    val description: String,
    val hasPassword: Boolean,
    val numPlayers: Int,
    val maxPlayers: Int,
    val inGame: Boolean,
    val hostID: Int
)

data class ChatMessage(
    val senderID: Int,
    val message: String,
    val timestamp: Long
)

object LANManager {

    // Native LAN functions - matches desktop melonDS MPInterface singleton pattern
    external fun lanSetMode(): Boolean
    external fun lanSetLocalMode()
    external fun lanSetRecvTimeout(timeout: Int)
    external fun lanStartHost(playerName: String, maxPlayers: Int, roomName: String, password: String?): Boolean
    external fun lanStartClient(playerName: String, hostAddress: String, password: String?): Boolean
    external fun lanStartDiscovery()
    external fun lanEndDiscovery()
    external fun lanEndSession()
    external fun lanIsInSession(): Boolean
    external fun lanIsHost(): Boolean
    external fun lanGetNumPlayers(): Int
    external fun lanGetMaxPlayers(): Int
    external fun lanGetDiscoveryList(): Array<String>?
    external fun lanGetPlayerList(): Array<String>?
    external fun lanProcess()
    
    // New room-based functions
    external fun lanGetRoomInfo(): Array<String>?
    external fun lanSendChatMessage(message: String)
    external fun lanGetChatMessages(): Array<String>?
    external fun lanKickPlayer(playerID: Int): Boolean
    external fun lanBanPlayer(playerID: Int): Boolean

    /**
     * Switch to LAN multiplayer mode.
     * Must be called before any other LAN operations (same as desktop setMPInterface(MPInterface_LAN)).
     */
    fun setMode(recvTimeoutMs: Int = 100): Boolean {
        val result = lanSetMode()
        if (result) {
            // Set the receive timeout from preferences
            setRecvTimeout(recvTimeoutMs)
        }
        return result
    }

    /**
     * Set the multiplayer receive timeout.
     * Default is 100ms. Can be adjusted for network conditions.
     */
    fun setRecvTimeout(timeoutMs: Int) {
        lanSetRecvTimeout(timeoutMs)
    }


    /**
     * Switch back to local multiplayer mode.
     * Call when leaving a room or cancelling (same as desktop setMPInterface(MPInterface_Local)).
     */
    fun setLocalMode() {
        lanSetLocalMode()
    }

    /**
     * Start hosting a LAN game with room information.
     * LAN mode must be set first via setMode().
     */
    fun startHost(playerName: String, maxPlayers: Int, roomName: String, password: String? = null): Boolean {
        return lanStartHost(playerName, maxPlayers, roomName, password)
    }

    /**
     * Join a LAN game as client with optional password.
     * LAN mode must be set first via setMode().
     * Discovery should be ended before joining.
     */
    fun startClient(playerName: String, hostAddress: String, password: String? = null): Boolean {
        return lanStartClient(playerName, hostAddress, password)
    }

    /**
     * Start discovering LAN games on the network.
     * LAN mode must be set first.
     */
    fun startDiscovery() {
        lanStartDiscovery()
    }

    /**
     * Stop discovering LAN games.
     */
    fun endDiscovery() {
        lanEndDiscovery()
    }

    /**
     * End current LAN session and disconnect.
     */
    fun endSession() {
        lanEndSession()
    }

    /**
     * Leave room: end session and switch back to local mode.
     * Matches desktop: lan().EndDiscovery(); lan().EndSession(); setMPInterface(MPInterface_Local)
     */
    fun leaveRoom() {
        endDiscovery()
        endSession()
        setLocalMode()
    }

    fun isInSession(): Boolean {
        return lanIsInSession()
    }

    fun isHost(): Boolean {
        return lanIsHost()
    }

    fun getNumPlayers(): Int {
        return lanGetNumPlayers()
    }

    fun getMaxPlayers(): Int {
        return lanGetMaxPlayers()
    }

    /**
     * Process LAN events (discovery + ENet).
     * Must be called regularly during discovery phase when no game is running.
     * During gameplay, the core's game loop already calls this automatically.
     */
    fun process() {
        lanProcess()
    }

    fun getAvailableRooms(): List<LANGameInfo> {
        val discoveryList = lanGetDiscoveryList() ?: return emptyList()

        return discoveryList.mapNotNull { entry ->
            try {
                // New format with room info: roomName|numPlayers|maxPlayers|roomCode|hasPassword|ipStr
                val parts = entry.split("|")
                if (parts.size >= 6) {
                    val roomName = parts[0]
                    val numPlayers = parts[1].toIntOrNull() ?: 0
                    val maxPlayers = parts[2].toIntOrNull() ?: 0
                    val roomCode = parts[3]
                    val hasPassword = parts[4] == "1"
                    val hostAddress = parts[5]

                    LANGameInfo(
                        hostName = "$roomName [$roomCode]",
                        gameName = "Nintendo DS",
                        playerCount = numPlayers,
                        maxPlayers = maxPlayers,
                        hostAddress = hostAddress
                    )
                } else if (parts.size >= 4) {
                    // Legacy format for backward compatibility
                    val sessionName = parts[0]
                    val numPlayers = parts[1].toIntOrNull() ?: 0
                    val maxPlayers = parts[2].toIntOrNull() ?: 0
                    val hostAddress = parts[3]

                    LANGameInfo(
                        hostName = sessionName,
                        gameName = "Nintendo DS",
                        playerCount = numPlayers,
                        maxPlayers = maxPlayers,
                        hostAddress = hostAddress
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    fun getPlayerListRaw(): Array<String>? {
        return lanGetPlayerList()
    }

    fun getRoomInfo(): RoomInfo? {
        val roomData = lanGetRoomInfo() ?: return null
        return try {
            // Format: roomCode|roomName|gameName|description|hasPassword|numPlayers|maxPlayers|inGame|hostID
            val parts = roomData[0].split("|")
            if (parts.size >= 9) {
                RoomInfo(
                    roomCode = parts[0],
                    roomName = parts[1],
                    gameName = parts[2],
                    description = parts[3],
                    hasPassword = parts[4] == "1",
                    numPlayers = parts[5].toIntOrNull() ?: 0,
                    maxPlayers = parts[6].toIntOrNull() ?: 0,
                    inGame = parts[7] == "1",
                    hostID = parts[8].toIntOrNull() ?: 0
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun sendChatMessage(message: String) {
        lanSendChatMessage(message)
    }

    fun getChatMessages(): List<ChatMessage> {
        val chatData = lanGetChatMessages() ?: return emptyList()
        return chatData.mapNotNull { entry ->
            try {
                // Format: senderID|message|timestamp
                val parts = entry.split("|")
                if (parts.size >= 3) {
                    ChatMessage(
                        senderID = parts[0].toIntOrNull() ?: -1,
                        message = parts[1],
                        timestamp = parts[2].toLongOrNull() ?: 0
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    fun kickPlayer(playerID: Int): Boolean {
        return lanKickPlayer(playerID)
    }

    fun banPlayer(playerID: Int): Boolean {
        return lanBanPlayer(playerID)
    }
}
