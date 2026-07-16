/*
    Copyright 2016-2026 melonDS team

    This file is part of melonDS.

    melonDS is free software: you can redistribute it and/or modify it under
    the terms of the GNU General Public License as published by the Free
    Software Foundation, either version 3 of the License, or (at your option)
    any later version.

    melonDS is distributed in the hope that it will be useful, but WITHOUT ANY
    WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
    FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with melonDS. If not, see http://www.gnu.org/licenses/.
*/

#include <stdio.h>
#include <string.h>

#ifdef __WIN32__
    #include <winsock2.h>
    #include <ws2tcpip.h>

    #define socket_t    SOCKET
    #define sockaddr_t  SOCKADDR
    #define sockaddr_in_t  SOCKADDR_IN
#else
    #include <unistd.h>
    #include <netinet/in.h>
    #include <sys/select.h>
    #include <sys/socket.h>

    #define socket_t    int
    #define sockaddr_t  struct sockaddr
    #define sockaddr_in_t  struct sockaddr_in
    #define closesocket close
#endif

#ifndef INVALID_SOCKET
    #define INVALID_SOCKET  (socket_t)-1
#endif

#include "LAN.h"


namespace melonDS
{

const u32 kDiscoveryMagic = 0x444E414C; // LAND
const u32 kLANMagic = 0x504E414C; // LANP
const u32 kPacketMagic = 0x4946494E; // NIFI

const u32 kProtocolVersion = 4; // Updated to match Citra/Azahar

const u32 kLocalhost = 0x0100007F;

// Helper function to generate random room code
static void GenerateRoomCode(char out[9])
{
    static const char chars[] = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    srand((unsigned)Platform::GetMSCount());
    for (int i = 0; i < 8; i++)
        out[i] = chars[rand() % 32];
    out[8] = '\0';
}

enum
{
    Chan_Cmd = 0,           // channel 0 -- control commands
    Chan_MP,                // channel 1 -- MP data exchange
    Chan_Chat,              // channel 2 -- chat messages
};

enum
{
    // Legacy commands (for backward compatibility)
    Cmd_ClientInit = 1,     // 01 -- host->client -- init new client and assign ID
    Cmd_PlayerInfo,         // 02 -- client->host -- send client player info to host
    Cmd_PlayerList,         // 03 -- host->client -- broadcast updated player list
    Cmd_PlayerConnect,      // 04 -- both -- signal connected state (ready to receive MP frames)
    Cmd_PlayerDisconnect,   // 05 -- both -- signal disconnected state (not receiving MP frames)
    
    // New room-based commands
    Cmd_RoomInfo = 10,      // 0A -- host->client -- send room information
    Cmd_RoomJoin,           // 0B -- client->host -- request to join room with password
    Cmd_RoomJoinAccept,     // 0C -- host->client -- accept room join
    Cmd_RoomJoinReject,     // 0D -- host->client -- reject room join (wrong password/full)
    Cmd_PeerList,           // 0E -- host->client -- send list of peer addresses for P2P
    Cmd_NodeMap,            // 0F -- host->client -- broadcast complete node map (Azahar-style)
    
    // Chat commands
    Cmd_ChatMessage = 20,   // 14 -- both -- send chat message
    
    // Moderation commands (host only)
    Cmd_KickPlayer = 30,    // 1E -- host->client -- kick player
    Cmd_BanPlayer = 31,     // 1F -- host->client -- ban player
};

const int kDiscoveryPort = 7063;
const int kLANPort = 7064;


LAN::LAN() noexcept : Inited(false)
{
    DiscoveryMutex = Platform::Mutex_Create();
    PlayersMutex = Platform::Mutex_Create();
    RoomMutex = Platform::Mutex_Create();
    ChatMutex = Platform::Mutex_Create();

    DiscoverySocket = INVALID_SOCKET;
    DiscoveryLastTick = 0;

    Active = false;
    IsHost = false;
    Host = nullptr;
    //Lag = false;

    memset(RemotePeers, 0, sizeof(RemotePeers));
    memset(Players, 0, sizeof(Players));
    memset(&CurrentRoom, 0, sizeof(CurrentRoom));
    NumPlayers = 0;
    MaxPlayers = 0;

    ConnectedBitmask = 0;

    MPRecvTimeout = 500; // Increased from 100ms to 500ms for more reliable data transfer
    LastHostID = -1;
    LastHostPeer = nullptr;

    FrameCount = 0;

    // TODO make this somewhat nicer
    if (enet_initialize() != 0)
    {
        Platform::Log(Platform::LogLevel::Error, "LAN: failed to initialize enet\n");
        return;
    }

    Platform::Log(Platform::LogLevel::Info, "LAN: enet initialized\n");
    Inited = true;
}

LAN::~LAN() noexcept
{
    EndSession();

    Inited = false;
    enet_deinitialize();

    Platform::Mutex_Free(DiscoveryMutex);
    Platform::Mutex_Free(PlayersMutex);
    Platform::Mutex_Free(RoomMutex);
    Platform::Mutex_Free(ChatMutex);

    Platform::Log(Platform::LogLevel::Info, "LAN: enet deinitialized\n");
}


std::map<u32, LAN::DiscoveryData> LAN::GetDiscoveryList()
{
    Platform::Mutex_Lock(DiscoveryMutex);
    auto ret = DiscoveryList;
    Platform::Mutex_Unlock(DiscoveryMutex);
    return ret;
}

LAN::RoomInfo LAN::GetRoomInfo()
{
    Platform::Mutex_Lock(RoomMutex);
    RoomInfo ret = CurrentRoom;
    Platform::Mutex_Unlock(RoomMutex);
    return ret;
}

std::vector<LAN::Player> LAN::GetPlayerList()
{
    Platform::Mutex_Lock(PlayersMutex);

    std::vector<Player> ret;
    for (int i = 0; i < 16; i++)
    {
        if (Players[i].Status == Player_None) continue;

        // make a copy of the player entry, fix up the address field
        Player newp = Players[i];
        if (newp.ID == MyPlayer.ID)
        {
            newp.IsLocalPlayer = true;
            newp.Address = kLocalhost;
        }
        else
        {
            newp.IsLocalPlayer = false;
            if (newp.Status == Player_Host)
                newp.Address = HostAddress;
        }

        ret.push_back(newp);
    }

    Platform::Mutex_Unlock(PlayersMutex);
    return ret;
}

void LAN::SendChatMessage(const char* message)
{
    if (!Active || !Host) return;

    u8 pkt[261]; // cmd(1) + senderID(4) + message(256)
    pkt[0] = Cmd_ChatMessage;
    pkt[1] = (u8)(MyPlayer.ID);
    pkt[2] = (u8)(MyPlayer.ID >> 8);
    pkt[3] = (u8)(MyPlayer.ID >> 16);
    pkt[4] = (u8)(MyPlayer.ID >> 24);
    memset(&pkt[5], 0, 256);
    strncpy((char*)&pkt[5], message, 255);

    ENetPacket* enetpkt = enet_packet_create(pkt, 261, ENET_PACKET_FLAG_RELIABLE);
    enet_host_broadcast(Host, Chan_Chat, enetpkt);
    enet_host_flush(Host); // Flush immediately to send chat message

    // Add to local chat history
    Platform::Mutex_Lock(ChatMutex);
    ChatMessage msg;
    msg.SenderID = MyPlayer.ID;
    strncpy(msg.Message, message, 255);
    msg.Timestamp = (u64)Platform::GetMSCount();
    ChatHistory.push_back(msg);
    // Keep last 100 messages
    if (ChatHistory.size() > 100)
        ChatHistory.erase(ChatHistory.begin());
    Platform::Mutex_Unlock(ChatMutex);
}

std::vector<LAN::ChatMessage> LAN::GetChatMessages()
{
    Platform::Mutex_Lock(ChatMutex);
    std::vector<ChatMessage> ret = ChatHistory;
    Platform::Mutex_Unlock(ChatMutex);
    return ret;
}

bool LAN::KickPlayer(int playerID)
{
    if (!Active || !IsHost) return false;
    if (playerID < 0 || playerID >= 16) return false;
    if (playerID == MyPlayer.ID) return false; // Can't kick yourself

    Platform::Mutex_Lock(PlayersMutex);
    if (Players[playerID].Status == Player_None)
    {
        Platform::Mutex_Unlock(PlayersMutex);
        return false;
    }
    Platform::Mutex_Unlock(PlayersMutex);

    u8 pkt[5]; // cmd(1) + playerID(4)
    pkt[0] = Cmd_KickPlayer;
    pkt[1] = (u8)playerID;
    pkt[2] = (u8)(playerID >> 8);
    pkt[3] = (u8)(playerID >> 16);
    pkt[4] = (u8)(playerID >> 24);

    ENetPacket* enetpkt = enet_packet_create(pkt, 5, ENET_PACKET_FLAG_RELIABLE);
    if (RemotePeers[playerID])
    {
        enet_peer_send(RemotePeers[playerID], Chan_Cmd, enetpkt);
        return true;
    }

    enet_packet_destroy(enetpkt);
    return false;
}

bool LAN::BanPlayer(int playerID)
{
    if (!Active || !IsHost) return false;
    if (playerID < 0 || playerID >= 16) return false;
    if (playerID == MyPlayer.ID) return false;

    Platform::Mutex_Lock(PlayersMutex);
    if (Players[playerID].Status == Player_None)
    {
        Platform::Mutex_Unlock(PlayersMutex);
        return false;
    }
    Platform::Mutex_Unlock(PlayersMutex);

    u8 pkt[5]; // cmd(1) + playerID(4)
    pkt[0] = Cmd_BanPlayer;
    pkt[1] = (u8)playerID;
    pkt[2] = (u8)(playerID >> 8);
    pkt[3] = (u8)(playerID >> 16);
    pkt[4] = (u8)(playerID >> 24);

    ENetPacket* enetpkt = enet_packet_create(pkt, 5, ENET_PACKET_FLAG_RELIABLE);
    if (RemotePeers[playerID])
    {
        enet_peer_send(RemotePeers[playerID], Chan_Cmd, enetpkt);
        // Disconnect the peer
        enet_peer_disconnect(RemotePeers[playerID], 0);
        return true;
    }

    enet_packet_destroy(enetpkt);
    return false;
}

bool LAN::VerifyPassword(const char* password)
{
    if (!password || strlen(password) == 0)
    {
        return CurrentRoom.HasPassword == 0;
    }

    if (CurrentRoom.HasPassword == 0)
    {
        return true; // No password set, allow entry
    }

    return strcmp(password, CurrentRoom.Password) == 0;
}

bool LAN::ConnectToPeer(int playerID, const char* address)
{
    if (!Active || !Host) return false;
    if (playerID < 0 || playerID >= 16) return false;
    if (RemotePeers[playerID]) return true; // Already connected

    ENetAddress addr;
    enet_address_set_host(&addr, address);
    addr.port = kLANPort;
    
    ENetPeer* peer = enet_host_connect(Host, &addr, 3, 0);
    if (!peer)
    {
        Platform::Log(Platform::LogLevel::Error, "LAN: failed to connect to peer %d at %s\n", playerID, address);
        return false;
    }

    Platform::Log(Platform::LogLevel::Info, "LAN: connecting to peer %d at %s\n", playerID, address);
    RemotePeers[playerID] = peer;
    peer->data = &Players[playerID];
    return true;
}

void LAN::DisconnectFromPeer(int playerID)
{
    if (playerID < 0 || playerID >= 16) return;
    if (!RemotePeers[playerID]) return;

    enet_peer_disconnect(RemotePeers[playerID], 0);
    RemotePeers[playerID] = nullptr;
    Platform::Log(Platform::LogLevel::Info, "LAN: disconnected from peer %d\n", playerID);
}

void LAN::BroadcastPeerList()
{
    if (!Active || !Host) return;

    // Send peer addresses to all connected peers
    u8 cmd[1 + 16 * 6]; // cmd(1) + 16 peers * (id(1) + addr(4) + port(1))
    cmd[0] = Cmd_PeerList;
    
    int offset = 1;
    Platform::Mutex_Lock(PlayersMutex);
    for (int i = 0; i < 16; i++)
    {
        if (Players[i].Status != Player_None && Players[i].Status != Player_Disconnected)
        {
            cmd[offset++] = (u8)i;
            cmd[offset++] = (u8)(Players[i].Address);
            cmd[offset++] = (u8)(Players[i].Address >> 8);
            cmd[offset++] = (u8)(Players[i].Address >> 16);
            cmd[offset++] = (u8)(Players[i].Address >> 24);
            cmd[offset++] = 0; // Port (use default LAN port)
        }
    }
    Platform::Mutex_Unlock(PlayersMutex);

    ENetPacket* pkt = enet_packet_create(cmd, offset, ENET_PACKET_FLAG_RELIABLE);
    enet_host_broadcast(Host, Chan_Cmd, pkt);
    Platform::Log(Platform::LogLevel::Info, "LAN: broadcast peer list to all clients\n");
}


bool LAN::StartDiscovery()
{
    if (!Inited) return false;

    int res;

    DiscoverySocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (DiscoverySocket < 0)
    {
        DiscoverySocket = INVALID_SOCKET;
        return false;
    }

    // Set socket options for cross-platform compatibility
    int opt_true = 1;
    int opt_false = 0;
    setsockopt(DiscoverySocket, SOL_SOCKET, SO_BROADCAST, (const char*)&opt_true, sizeof(int));
    setsockopt(DiscoverySocket, SOL_SOCKET, SO_REUSEADDR, (const char*)&opt_true, sizeof(int));
    
#ifdef SO_REUSEPORT
    setsockopt(DiscoverySocket, SOL_SOCKET, SO_REUSEPORT, (const char*)&opt_true, sizeof(int));
#endif

    sockaddr_in_t saddr;
    memset(&saddr, 0, sizeof(saddr));
    saddr.sin_family = AF_INET;
    saddr.sin_addr.s_addr = htonl(INADDR_ANY);
    saddr.sin_port = htons(kDiscoveryPort);
    res = bind(DiscoverySocket, (const sockaddr_t*)&saddr, sizeof(saddr));
    if (res < 0)
    {
        closesocket(DiscoverySocket);
        DiscoverySocket = INVALID_SOCKET;
        return false;
    }

    DiscoveryLastTick = (u32)Platform::GetMSCount();
    DiscoveryList.clear();

    Active = true;
    return true;
}

void LAN::EndDiscovery()
{
    if (!Inited) return;

    if (DiscoverySocket != INVALID_SOCKET)
    {
        closesocket(DiscoverySocket);
        DiscoverySocket = INVALID_SOCKET;
    }

    if (!IsHost)
        Active = false;
}

bool LAN::StartHost(const char* playername, int numplayers, const char* roomName, const char* password)
{
    if (!Inited) return false;
    if (numplayers > 16) return false;

    ENetAddress addr;
    addr.host = ENET_HOST_ANY;
    addr.port = kLANPort;

    Host = enet_host_create(&addr, 16, 3, 0, 0); // 16 peers for P2P mesh, 3 channels (Cmd, MP, Chat)
    if (!Host)
    {
        return false;
    }

    Platform::Mutex_Lock(PlayersMutex);
    Platform::Mutex_Lock(RoomMutex);

    Player* player = &Players[0];
    memset(player, 0, sizeof(Player));
    player->ID = 0;
    strncpy(player->Name, playername, 31);
    player->Status = Player_Host;
    player->Address = kLocalhost;
    NumPlayers = 1;
    MaxPlayers = numplayers;
    memcpy(&MyPlayer, player, sizeof(Player));

    // Initialize room info
    memset(&CurrentRoom, 0, sizeof(CurrentRoom));
    GenerateRoomCode(CurrentRoom.RoomCode);
    strncpy(CurrentRoom.RoomName, roomName ? roomName : playername, 63);
    strncpy(CurrentRoom.GameName, "Nintendo DS", 63);
    strncpy(CurrentRoom.Description, "", 127);
    if (password && strlen(password) > 0)
    {
        strncpy(CurrentRoom.Password, password, 32);
        CurrentRoom.HasPassword = 1;
    }
    else
    {
        CurrentRoom.HasPassword = 0;
    }
    CurrentRoom.NumPlayers = NumPlayers;
    CurrentRoom.MaxPlayers = MaxPlayers;
    CurrentRoom.InGame = 0;
    CurrentRoom.HostID = 0;

    Platform::Mutex_Unlock(RoomMutex);
    Platform::Mutex_Unlock(PlayersMutex);

    HostAddress = kLocalhost;
    LastHostID = -1;
    LastHostPeer = nullptr;

    Active = true;
    IsHost = true;

    StartDiscovery();
    Platform::Log(Platform::LogLevel::Info, "LAN: host started room [%s] '%s' on port %d\n", CurrentRoom.RoomCode, CurrentRoom.RoomName, kLANPort);
    
    // Force immediate discovery broadcast by calling ProcessDiscovery
    ProcessDiscovery();
    Platform::Log(Platform::LogLevel::Info, "LAN: sent initial discovery broadcast\n");
    
    return true;
}

bool LAN::StartClient(const char* playername, const char* host, const char* password)
{
    if (!Inited) return false;

    Host = enet_host_create(nullptr, 16, 3, 0, 0); // 16 peers for P2P mesh, 3 channels
    if (!Host)
    {
        return false;
    }

    ENetAddress addr;
    enet_address_set_host(&addr, host);
    addr.port = kLANPort;
    ENetPeer* peer = enet_host_connect(Host, &addr, 3, 0);
    if (!peer)
    {
        Platform::Log(Platform::LogLevel::Error, "LAN: client failed to connect - no connection established\n");
        enet_host_destroy(Host);
        Host = nullptr;
        return false;
    }

    Platform::Mutex_Lock(PlayersMutex);

    Player* player = &MyPlayer;
    memset(player, 0, sizeof(Player));
    player->ID = 0;
    strncpy(player->Name, playername, 31);
    player->Status = Player_Connecting;

    Platform::Mutex_Unlock(PlayersMutex);

    ENetEvent event;
    int conn = 0;
    u32 starttick = (u32)Platform::GetMSCount();
    const int conntimeout = 10000; // Increased from 5000ms to 10000ms for better reliability
    Platform::Log(Platform::LogLevel::Info, "LAN: client connecting to %s:%d, timeout=%dms\n", host, kLANPort, conntimeout);
    
    for (;;)
    {
        u32 curtick = (u32)Platform::GetMSCount();
        if (curtick < starttick) break;
        int timeout = conntimeout - (int)(curtick - starttick);
        if (timeout < 0) {
            Platform::Log(Platform::LogLevel::Error, "LAN: client connection timeout after %dms\n", conntimeout);
            break;
        }
        
        if (enet_host_service(Host, &event, timeout) > 0)
        {
            Platform::Log(Platform::LogLevel::Info, "LAN: client got event type=%d\n", event.type);
            if (conn == 0 && event.type == ENET_EVENT_TYPE_CONNECT)
            {
                Platform::Log(Platform::LogLevel::Info, "LAN: client connected successfully\n");
                conn = 1;
            }
            else if (conn == 1 && event.type == ENET_EVENT_TYPE_RECEIVE)
            {
                u8* data = event.packet->data;
                if (event.channelID != Chan_Cmd) continue;
                if (data[0] != Cmd_ClientInit) continue;
                if (event.packet->dataLength != 11) continue;

                u32 magic = data[1] | (data[2] << 8) | (data[3] << 16) | (data[4] << 24);
                u32 version = data[5] | (data[6] << 8) | (data[7] << 16) | (data[8] << 24);
                if (magic != kLANMagic) continue;
                if (data[10] > 16) continue;

                MaxPlayers = data[10];
                MyPlayer.ID = data[9];

                Platform::Log(Platform::LogLevel::Info, "LAN: client received Cmd_ClientInit, magic=0x%08X, version=%d\n", magic, version);
                
                // Check protocol version for backward compatibility
                if (version == 1)
                {
                    Platform::Log(Platform::LogLevel::Info, "LAN: client using legacy protocol v1\n");
                    // Legacy protocol (desktop melonDS) - send PlayerInfo directly
                    u8 cmd[9+sizeof(Player)];
                    cmd[0] = Cmd_PlayerInfo;
                    cmd[1] = (u8)kLANMagic;
                    cmd[2] = (u8)(kLANMagic >> 8);
                    cmd[3] = (u8)(kLANMagic >> 16);
                    cmd[4] = (u8)(kLANMagic >> 24);
                    cmd[5] = (u8)1; // Protocol version 1 for compatibility
                    cmd[6] = 0;
                    cmd[7] = 0;
                    cmd[8] = 0;
                    memcpy(&cmd[9], &MyPlayer, sizeof(Player));
                    ENetPacket* pkt = enet_packet_create(cmd, 9+sizeof(Player), ENET_PACKET_FLAG_RELIABLE);
                    enet_peer_send(event.peer, Chan_Cmd, pkt);
                    enet_host_flush(Host);
                }
                else if (version == kProtocolVersion)
                {
                    Platform::Log(Platform::LogLevel::Info, "LAN: client using new protocol v%d\n", kProtocolVersion);
                    // New protocol (room-based) - send RoomJoin with password
                    u8 cmd[1 + 4 + 4 + 32 + sizeof(Player)]; // cmd(1) + magic(4) + version(4) + password(32) + player struct
                    cmd[0] = Cmd_RoomJoin;
                    cmd[1] = (u8)kLANMagic;
                    cmd[2] = (u8)(kLANMagic >> 8);
                    cmd[3] = (u8)(kLANMagic >> 16);
                    cmd[4] = (u8)(kLANMagic >> 24);
                    cmd[5] = (u8)kProtocolVersion;
                    cmd[6] = (u8)(kProtocolVersion >> 8);
                    cmd[7] = (u8)(kProtocolVersion >> 16);
                    cmd[8] = (u8)(kProtocolVersion >> 24);
                    memset(&cmd[9], 0, 32);
                    if (password)
                        strncpy((char*)&cmd[9], password, 31);
                    memcpy(&cmd[41], &MyPlayer, sizeof(Player));
                    ENetPacket* pkt = enet_packet_create(cmd, 41 + sizeof(Player), ENET_PACKET_FLAG_RELIABLE);
                    enet_peer_send(event.peer, Chan_Cmd, pkt);
                    enet_host_flush(Host);
                }
                else
                {
                    Platform::Log(Platform::LogLevel::Error, "LAN: client unsupported protocol version %d\n", version);
                    // Unsupported protocol version
                    enet_peer_disconnect(event.peer, 0);
                    conn = 0;
                    break;
                }

                conn = 2;
                break;
            }
            else if (event.type == ENET_EVENT_TYPE_DISCONNECT)
            {
                conn = 0;
                break;
            }
        }
        else
            break;
    }

    if (conn != 2)
    {
        Platform::Log(Platform::LogLevel::Error, "LAN: client connection failed - conn state=%d\n", conn);
        enet_peer_reset(peer);
        enet_host_destroy(Host);
        Host = nullptr;
        return false;
    }

    HostAddress = addr.host;
    LastHostID = -1;
    LastHostPeer = nullptr;
    RemotePeers[0] = peer;
    peer->data = &Players[0];

    Platform::Log(Platform::LogLevel::Info, "LAN: client session established successfully\n");
    Active = true;
    IsHost = false;
    return true;
}

void LAN::EndSession()
{
    if (!Active) return;
    if (IsHost) EndDiscovery();

    Active = false;

    while (!RXQueue.empty())
    {
        ENetPacket* packet = RXQueue.front();
        RXQueue.pop();
        enet_packet_destroy(packet);
    }

    for (int i = 0; i < 16; i++)
    {
        if (i == MyPlayer.ID) continue;

        if (RemotePeers[i])
            enet_peer_disconnect(RemotePeers[i], 0);

        RemotePeers[i] = nullptr;
    }

    enet_host_destroy(Host);
    Host = nullptr;
    IsHost = false;
}


void LAN::ProcessDiscovery()
{
    if (DiscoverySocket == INVALID_SOCKET)
        return;

    u32 tick = (u32)Platform::GetMSCount();
    if ((tick - DiscoveryLastTick) < 1000)
        return;

    DiscoveryLastTick = tick;

    if (IsHost)
    {
        // advertise this LAN session over the network with room info
        // Send both legacy (v1) and new (v4) formats for compatibility

        // Legacy format for desktop melonDS compatibility
        struct LegacyDiscoveryData
        {
            u32 Magic;
            u32 Version;
            u32 Tick;
            char SessionName[64];
            u8 NumPlayers;
            u8 MaxPlayers;
            u8 Status;
            u8 _pad;
        };

        LegacyDiscoveryData legacyBeacon;
        memset(&legacyBeacon, 0, sizeof(legacyBeacon));
        legacyBeacon.Magic = kDiscoveryMagic;
        legacyBeacon.Version = 1; // Version 1 for desktop compatibility
        legacyBeacon.Tick = tick;
        snprintf(legacyBeacon.SessionName, 64, "%s's game", MyPlayer.Name);
        legacyBeacon.NumPlayers = NumPlayers;
        legacyBeacon.MaxPlayers = MaxPlayers;
        legacyBeacon.Status = 0;

        sockaddr_in_t saddr;
        memset(&saddr, 0, sizeof(saddr));
        saddr.sin_family = AF_INET;
        saddr.sin_addr.s_addr = htonl(INADDR_BROADCAST);
        saddr.sin_port = htons(kDiscoveryPort);

        // Send broadcast for standard LAN discovery
        sendto(DiscoverySocket, (const char*)&legacyBeacon, sizeof(legacyBeacon), 0, (const sockaddr_t*)&saddr, sizeof(saddr));
        
        // Also send to local subnet for cross-platform compatibility (mobile/desktop)
        saddr.sin_addr.s_addr = htonl(INADDR_ANY);
        sendto(DiscoverySocket, (const char*)&legacyBeacon, sizeof(legacyBeacon), 0, (const sockaddr_t*)&saddr, sizeof(saddr));

        // New format for Android clients
        DiscoveryData newBeacon;
        memset(&newBeacon, 0, sizeof(newBeacon));
        newBeacon.Magic = kDiscoveryMagic;
        newBeacon.Version = kProtocolVersion;
        newBeacon.Tick = tick;
        
        Platform::Mutex_Lock(RoomMutex);
        newBeacon.Room = CurrentRoom;
        newBeacon.Room.NumPlayers = NumPlayers;
        newBeacon.Room.MaxPlayers = MaxPlayers;
        Platform::Mutex_Unlock(RoomMutex);

        sendto(DiscoverySocket, (const char*)&newBeacon, sizeof(newBeacon), 0, (const sockaddr_t*)&saddr, sizeof(saddr));
        Platform::Log(Platform::LogLevel::Info, "LAN: discovery broadcast sent (legacy + new)\n");
    }
    else
    {
        Platform::Mutex_Lock(DiscoveryMutex);

        // listen for LAN sessions

        fd_set fd;
        struct timeval tv;
        for (;;)
        {
            FD_ZERO(&fd); FD_SET(DiscoverySocket, &fd);
            tv.tv_sec = 0; tv.tv_usec = 0;
            if (!select(DiscoverySocket+1, &fd, nullptr, nullptr, &tv))
                break;

            u8 buffer[512];
            sockaddr_in_t raddr;
            socklen_t ralen = sizeof(raddr);

            int rlen = recvfrom(DiscoverySocket, (char*)buffer, sizeof(buffer), 0, (sockaddr_t*)&raddr, &ralen);
            if (rlen < 16) continue; // Minimum: magic(4) + version(4) + tick(4) + some data

            u32 magic = buffer[0] | (buffer[1] << 8) | (buffer[2] << 16) | (buffer[3] << 24);
            if (magic != kDiscoveryMagic) continue;

            u32 version = buffer[4] | (buffer[5] << 8) | (buffer[6] << 16) | (buffer[7] << 24);
            if (version > kProtocolVersion) continue; // Reject future versions we don't understand

            DiscoveryData beacon;
            memset(&beacon, 0, sizeof(beacon));
            beacon.Magic = magic;
            beacon.Version = version;
            beacon.Tick = buffer[8] | (buffer[9] << 8) | (buffer[10] << 16) | (buffer[11] << 24);

            if (version == 1 && rlen >= 76) // Legacy format
            {
                // Parse legacy discovery data
                char sessionName[64];
                memcpy(sessionName, &buffer[12], 64);
                sessionName[63] = '\0';
                beacon.Room.NumPlayers = buffer[76];
                beacon.Room.MaxPlayers = buffer[77];
                snprintf(beacon.Room.RoomName, 64, "%s", sessionName);
                beacon.Room.RoomCode[0] = '\0'; // No room code in legacy
                beacon.Room.HasPassword = 0;
            }
            else if (version == kProtocolVersion && rlen >= (int)sizeof(DiscoveryData))
            {
                // Parse new discovery data
                memcpy(&beacon, buffer, sizeof(DiscoveryData));
            }
            else
            {
                continue; // Unknown format or incomplete packet
            }

            u32 key = ntohl(raddr.sin_addr.s_addr);

            if (DiscoveryList.find(key) != DiscoveryList.end())
            {
                if (beacon.Tick <= DiscoveryList[key].Tick)
                    continue;
            }

            beacon.Magic = tick; // Store tick in Magic field for age tracking
            DiscoveryList[key] = beacon;
            Platform::Log(Platform::LogLevel::Info, "LAN: discovery received from %s (v%d, room: %s, players: %d/%d)\n", 
                inet_ntoa(raddr.sin_addr), version, beacon.Room.RoomName, beacon.Room.NumPlayers, beacon.Room.MaxPlayers);
        }

        // cleanup: remove hosts that haven't given a sign of life in the last 5 seconds

        std::vector<u32> deletelist;

        for (const auto& [key, data] : DiscoveryList)
        {
            u32 age = tick - data.Magic;
            if (age < 5000) continue;

            deletelist.push_back(key);
        }

        for (const auto& key : deletelist)
        {
            DiscoveryList.erase(key);
        }

        Platform::Mutex_Unlock(DiscoveryMutex);
    }
}

void LAN::HostUpdatePlayerList()
{
    u8 cmd[2+sizeof(Players)];
    cmd[0] = Cmd_PlayerList;
    cmd[1] = (u8)NumPlayers;
    memcpy(&cmd[2], Players, sizeof(Players));
    ENetPacket* pkt = enet_packet_create(cmd, 2+sizeof(Players), ENET_PACKET_FLAG_RELIABLE);
    enet_host_broadcast(Host, Chan_Cmd, pkt);
}

void LAN::HostUpdateRoomInfo()
{
    u8 cmd[1+sizeof(RoomInfo)];
    cmd[0] = Cmd_RoomInfo;
    Platform::Mutex_Lock(RoomMutex);
    memcpy(&cmd[1], &CurrentRoom, sizeof(RoomInfo));
    Platform::Mutex_Unlock(RoomMutex);
    ENetPacket* pkt = enet_packet_create(cmd, 1+sizeof(RoomInfo), ENET_PACKET_FLAG_RELIABLE);
    enet_host_broadcast(Host, Chan_Cmd, pkt);
}

void LAN::ClientUpdatePlayerList()
{
}

void LAN::ProcessHostEvent(ENetEvent& event)
{
    switch (event.type)
    {
    case ENET_EVENT_TYPE_CONNECT:
        {
            if ((NumPlayers >= MaxPlayers) || (NumPlayers >= 16))
            {
                // game is full, reject connection
                enet_peer_disconnect(event.peer, 0);
                break;
            }

            // client connected; assign player number

            int id;
            for (id = 0; id < 16; id++)
            {
                if (id >= NumPlayers) break;
                if (Players[id].Status == Player_None) break;
            }

            if (id < 16)
            {
                u8 cmd[11];
                cmd[0] = Cmd_ClientInit;
                cmd[1] = (u8)kLANMagic;
                cmd[2] = (u8)(kLANMagic >> 8);
                cmd[3] = (u8)(kLANMagic >> 16);
                cmd[4] = (u8)(kLANMagic >> 24);
                cmd[5] = (u8)kProtocolVersion;
                cmd[6] = (u8)(kProtocolVersion >> 8);
                cmd[7] = (u8)(kProtocolVersion >> 16);
                cmd[8] = (u8)(kProtocolVersion >> 24);
                cmd[9] = (u8)id;
                cmd[10] = MaxPlayers;
                ENetPacket* pkt = enet_packet_create(cmd, 11, ENET_PACKET_FLAG_RELIABLE);
                enet_peer_send(event.peer, Chan_Cmd, pkt);

                Platform::Mutex_Lock(PlayersMutex);

                Players[id].ID = id;
                Players[id].Status = Player_Connecting;
                Players[id].Address = event.peer->address.host;
                event.peer->data = &Players[id];
                NumPlayers++;

                Platform::Mutex_Unlock(PlayersMutex);

                RemotePeers[id] = event.peer;
            }
            else
            {
                // ???
                enet_peer_disconnect(event.peer, 0);
            }
        }
        break;

    case ENET_EVENT_TYPE_DISCONNECT:
        {
            Player* player = (Player*)event.peer->data;
            if (!player) break;

            ConnectedBitmask &= ~(1 << player->ID);

            int id = player->ID;
            RemotePeers[id] = nullptr;

            player->ID = 0;
            player->Status = Player_None;
            NumPlayers--;

            // broadcast updated player list
            HostUpdatePlayerList();
        }
        break;

    case ENET_EVENT_TYPE_RECEIVE:
        {
            if (event.packet->dataLength < 1) break;

            u8* data = (u8*)event.packet->data;
            switch (data[0])
            {
            case Cmd_PlayerInfo: // client sending player info
                {
                    if (event.packet->dataLength != (9+sizeof(Player))) break;

                    u32 magic = data[1] | (data[2] << 8) | (data[3] << 16) | (data[4] << 24);
                    u32 version = data[5] | (data[6] << 8) | (data[7] << 16) | (data[8] << 24);
                    if ((magic != kLANMagic) || (version != kProtocolVersion))
                    {
                        enet_peer_disconnect(event.peer, 0);
                        break;
                    }

                    Player player;
                    memcpy(&player, &data[9], sizeof(Player));
                    player.Name[31] = '\0';

                    Player* hostside = (Player*)event.peer->data;
                    if (player.ID != hostside->ID)
                    {
                        enet_peer_disconnect(event.peer, 0);
                        break;
                    }

                    Platform::Mutex_Lock(PlayersMutex);

                    player.Status = Player_Client;
                    player.Address = event.peer->address.host;
                    memcpy(hostside, &player, sizeof(Player));

                    Platform::Mutex_Unlock(PlayersMutex);

                    // broadcast updated player list
                    HostUpdatePlayerList();
                    
                    // broadcast peer list for P2P connections
                    BroadcastPeerList();
                    
                    // broadcast complete node map (Azahar-style)
                    BroadcastNodeMap();
                }
                break;

            case Cmd_PlayerConnect: // player connected
                {
                    if (event.packet->dataLength != 1) break;
                    Player* player = (Player*)event.peer->data;
                    if (!player) break;

                    ConnectedBitmask |= (1 << player->ID);
                }
                break;

            case Cmd_PlayerDisconnect: // player disconnected
                {
                    if (event.packet->dataLength != 1) break;
                    Player* player = (Player*)event.peer->data;
                    if (!player) break;

                    ConnectedBitmask &= ~(1 << player->ID);
                }
                break;

            case Cmd_RoomJoin: // client requesting to join room with password
                {
                    if (event.packet->dataLength < (1 + 4 + 4 + 32 + sizeof(Player))) break;
                    
                    u32 magic = data[1] | (data[2] << 8) | (data[3] << 16) | (data[4] << 24);
                    u32 version = data[5] | (data[6] << 8) | (data[7] << 16) | (data[8] << 24);
                    if (magic != kLANMagic || version != kProtocolVersion) break;
                    
                    char password[33];
                    memset(password, 0, 33);
                    memcpy(password, &data[9], 32);
                    
                    Player player;
                    memcpy(&player, &data[41], sizeof(Player));
                    player.Name[31] = '\0';
                    
                    Player* hostside = (Player*)event.peer->data;
                    if (player.ID != hostside->ID) break;
                    
                    // Verify password
                    if (!VerifyPassword(password))
                    {
                        // Send reject
                        u8 cmd[1];
                        cmd[0] = Cmd_RoomJoinReject;
                        ENetPacket* pkt = enet_packet_create(cmd, 1, ENET_PACKET_FLAG_RELIABLE);
                        enet_peer_send(event.peer, Chan_Cmd, pkt);
                        enet_peer_disconnect(event.peer, 0);
                        break;
                    }
                    
                    // Accept join
                    Platform::Mutex_Lock(PlayersMutex);
                    player.Status = Player_Client;
                    player.Address = event.peer->address.host;
                    memcpy(hostside, &player, sizeof(Player));
                    Platform::Mutex_Unlock(PlayersMutex);
                    
                    // Send accept
                    u8 cmd[1 + sizeof(RoomInfo)];
                    cmd[0] = Cmd_RoomJoinAccept;
                    Platform::Mutex_Lock(RoomMutex);
                    memcpy(&cmd[1], &CurrentRoom, sizeof(RoomInfo));
                    Platform::Mutex_Unlock(RoomMutex);
                    ENetPacket* pkt = enet_packet_create(cmd, 1 + sizeof(RoomInfo), ENET_PACKET_FLAG_RELIABLE);
                    enet_peer_send(event.peer, Chan_Cmd, pkt);
                    
                    // Send player list
                    HostUpdatePlayerList();
                    HostUpdateRoomInfo();
                    
                    // broadcast complete node map (Azahar-style)
                    BroadcastNodeMap();
                }
                break;

            case Cmd_ChatMessage: // chat message
                {
                    if (event.packet->dataLength < 261) break;
                    
                    u32 senderID = data[1] | (data[2] << 8) | (data[3] << 16) | (data[4] << 24);
                    char message[256];
                    memcpy(message, &data[5], 255);
                    message[255] = '\0';
                    
                    // Add to chat history
                    Platform::Mutex_Lock(ChatMutex);
                    ChatMessage msg;
                    msg.SenderID = senderID;
                    strncpy(msg.Message, message, 255);
                    msg.Timestamp = (u64)Platform::GetMSCount();
                    ChatHistory.push_back(msg);
                    if (ChatHistory.size() > 100)
                        ChatHistory.erase(ChatHistory.begin());
                    Platform::Mutex_Unlock(ChatMutex);
                    
                    // Broadcast to all clients
                    ENetPacket* pkt = enet_packet_create(event.packet->data, event.packet->dataLength, ENET_PACKET_FLAG_RELIABLE);
                    enet_host_broadcast(Host, Chan_Chat, pkt);
                }
                break;
            }

            enet_packet_destroy(event.packet);
        }
        break;
    case ENET_EVENT_TYPE_NONE:
        break;
    }
}

void LAN::ProcessClientEvent(ENetEvent& event)
{
    switch (event.type)
    {
    case ENET_EVENT_TYPE_CONNECT:
        {
            // another client is establishing a direct connection to us

            int playerid = -1;
            for (int i = 0; i < 16; i++)
            {
                Player* player = &Players[i];
                if (i == MyPlayer.ID) continue;
                if (player->Status != Player_Client) continue;

                if (player->Address == event.peer->address.host)
                {
                    playerid = i;
                    break;
                }
            }

            if (playerid < 0)
            {
                enet_peer_disconnect(event.peer, 0);
                break;
            }

            RemotePeers[playerid] = event.peer;
            event.peer->data = &Players[playerid];
        }
        break;

    case ENET_EVENT_TYPE_DISCONNECT:
        {
            Player* player = (Player*)event.peer->data;
            if (!player) break;

            ConnectedBitmask &= ~(1 << player->ID);

            int id = player->ID;
            RemotePeers[id] = nullptr;

            Platform::Mutex_Lock(PlayersMutex);
            player->Status = Player_Disconnected;
            Platform::Mutex_Unlock(PlayersMutex);

            ClientUpdatePlayerList();
        }
        break;

    case ENET_EVENT_TYPE_RECEIVE:
        {
            if (event.packet->dataLength < 1) break;

            u8* data = (u8*)event.packet->data;
            switch (data[0])
            {
            case Cmd_PlayerList: // host sending player list
                {
                    if (event.packet->dataLength != (2+sizeof(Players))) break;
                    if (data[1] > 16) break;

                    Platform::Mutex_Lock(PlayersMutex);

                    NumPlayers = data[1];
                    memcpy(Players, &data[2], sizeof(Players));
                    for (int i = 0; i < 16; i++)
                    {
                        Players[i].Name[31] = '\0';
                    }

                    Platform::Mutex_Unlock(PlayersMutex);
                }
                break;

            case Cmd_PeerList: // host sending peer addresses for P2P
                {
                    if (event.packet->dataLength < 1) break;
                    int numPeers = (event.packet->dataLength - 1) / 6; // Each peer: id(1) + addr(4) + port(1)
                    
                    int offset = 1;
                    for (int i = 0; i < numPeers; i++)
                    {
                        if (offset + 6 > event.packet->dataLength) break;
                        
                        int peerID = data[offset++];
                        u32 addr = data[offset] | (data[offset+1] << 8) | (data[offset+2] << 16) | (data[offset+3] << 24);
                        offset += 4;
                        u8 port = data[offset++];
                        
                        if (peerID == MyPlayer.ID) continue;
                        if (peerID < 0 || peerID >= 16) continue;
                        if (RemotePeers[peerID]) continue; // Already connected
                        
                        char addrStr[16];
                        snprintf(addrStr, 16, "%d.%d.%d.%d", (addr>>24), ((addr>>16)&0xFF), ((addr>>8)&0xFF), (addr&0xFF));
                        
                        Platform::Log(Platform::LogLevel::Info, "LAN: client connecting to peer %d at %s:%d\n", peerID, addrStr, port);
                        
                        ENetAddress peeraddr;
                        peeraddr.host = addr;
                        peeraddr.port = kLANPort;
                        ENetPeer* peer = enet_host_connect(Host, &peeraddr, 3, 0);
                        if (peer)
                        {
                            RemotePeers[peerID] = peer;
                            peer->data = &Players[peerID];
                        }
                        else
                        {
                            Platform::Log(Platform::LogLevel::Error, "LAN: failed to connect to peer %d\n", peerID);
                        }
                    }
                }
                break;

            case Cmd_PlayerConnect: // player connected
                {
                    if (event.packet->dataLength != 1) break;
                    Player* player = (Player*)event.peer->data;
                    if (!player) break;

                    ConnectedBitmask |= (1 << player->ID);
                }
                break;

            case Cmd_PlayerDisconnect: // player disconnected
                {
                    if (event.packet->dataLength != 1) break;
                    Player* player = (Player*)event.peer->data;
                    if (!player) break;

                    ConnectedBitmask &= ~(1 << player->ID);
                }
                break;

            case Cmd_NodeMap: // host broadcasting node map
                {
                    Platform::Log(Platform::LogLevel::Info, "LAN: received node map packet with %d bytes\n", event.packet->dataLength);
                    HandleNodeMapPacket(data, event.packet->dataLength);
                }
                break;
            }

            enet_packet_destroy(event.packet);
        }
        break;
    case ENET_EVENT_TYPE_NONE:
        break;
    }
}

void LAN::ProcessEvent(ENetEvent& event)
{
    if (IsHost)
        ProcessHostEvent(event);
    else
        ProcessClientEvent(event);
}

// 0 = per-frame processing of events and eventual misc. frame
// 1 = checking if a misc. frame has arrived
// 2 = waiting for a MP frame
void LAN::ProcessLAN(int type)
{
    if (!Host) return;

    u32 time_last = (u32)Platform::GetMSCount();

    // see if we have queued packets already, get rid of the stale ones
    // any incoming packet should be consumed by the core quickly, so if
    // they've been sitting in the queue for more than one frame's time,
    // we can assume they're stale
    while (!RXQueue.empty())
    {
        ENetPacket* enetpacket = RXQueue.front();
        MPPacketHeader* header = (MPPacketHeader*)&enetpacket->data[0];
        u32 packettime = header->Magic;

        if ((packettime > time_last) || (packettime < (time_last - 16)))
        {
            RXQueue.pop();
            enet_packet_destroy(enetpacket);
        }
        else
        {
            // we got a packet, depending on what the caller wants we might be able to return now
            if (type == 2) return;
            if (type == 1)
            {
                // if looking for a misc. frame, we shouldn't be receiving a MP frame
                if (header->Type == 0)
                    return;

                RXQueue.pop();
                enet_packet_destroy(enetpacket);
            }

            break;
        }
    }

    int timeout = (type == 2) ? MPRecvTimeout : 0;
    time_last = (u32)Platform::GetMSCount();

    ENetEvent event;
    while (enet_host_service(Host, &event, timeout) > 0)
    {
        if (event.type == ENET_EVENT_TYPE_RECEIVE && event.channelID == Chan_MP)
        {
            MPPacketHeader* header = (MPPacketHeader*)&event.packet->data[0];

            bool good = true;
            if (event.packet->dataLength < sizeof(MPPacketHeader))
                good = false;
            else if (header->Magic != 0x4946494E)
                good = false;
            else if (header->SenderID == MyPlayer.ID)
                good = false;

            if (!good)
            {
                enet_packet_destroy(event.packet);
            }
            else
            {
                // mark this packet with the time it was received
                header->Magic = (u32)Platform::GetMSCount();

                event.packet->userData = event.peer;
                RXQueue.push(event.packet);

                // return now -- if we are receiving MP frames, if we keep going
                // we'll consume too many even if we have no timeout set
                return;
            }
        }
        else
        {
            ProcessEvent(event);
        }

        if (type == 2)
        {
            u32 time = (u32)Platform::GetMSCount();
            if (time < time_last) return;
            timeout -= (int)(time - time_last);
            if (timeout <= 0) return;
            time_last = time;
        }
    }
}

void LAN::Process()
{
    if (!Active) return;

    ProcessDiscovery();
    ProcessLAN(0);

    FrameCount++;
    if (FrameCount >= 120) // Reduced frequency from 60 to 120 frames for performance
    {
        FrameCount = 0;

        Platform::Mutex_Lock(PlayersMutex);

        for (int i = 0; i < 16; i++)
        {
            if (Players[i].Status == Player_None) continue;
            if (i == MyPlayer.ID) continue;
            if (!RemotePeers[i]) continue;

            Players[i].Ping = RemotePeers[i]->roundTripTime;
        }

        Platform::Mutex_Unlock(PlayersMutex);
    }
}


void LAN::Begin(int inst)
{
    if (!Host) return;

    ConnectedBitmask |= (1 << MyPlayer.ID);
    LastHostID = -1;
    LastHostPeer = nullptr;

    u8 cmd = Cmd_PlayerConnect;
    ENetPacket* pkt = enet_packet_create(&cmd, 1, ENET_PACKET_FLAG_RELIABLE);
    enet_host_broadcast(Host, Chan_Cmd, pkt);
}

void LAN::End(int inst)
{
    if (!Host) return;

    ConnectedBitmask &= ~(1 << MyPlayer.ID);

    u8 cmd = Cmd_PlayerDisconnect;
    ENetPacket* pkt = enet_packet_create(&cmd, 1, ENET_PACKET_FLAG_RELIABLE);
    enet_host_broadcast(Host, Chan_Cmd, pkt);
}


int LAN::SendPacketGeneric(u32 type, u8* packet, int len, u64 timestamp)
{
    if (!Host) return 0;

    // TODO make the reliable part optional?
    //u32 flags = ENET_PACKET_FLAG_RELIABLE;
    u32 flags = ENET_PACKET_FLAG_UNSEQUENCED;

    ENetPacket* enetpacket = enet_packet_create(nullptr, sizeof(MPPacketHeader)+len, flags);

    MPPacketHeader pktheader;
    pktheader.Magic = 0x4946494E;
    pktheader.SenderID = MyPlayer.ID;
    pktheader.Type = type;
    pktheader.Length = len;
    pktheader.Timestamp = timestamp;
    memcpy(&enetpacket->data[0], &pktheader, sizeof(MPPacketHeader));
    if (len)
        memcpy(&enetpacket->data[sizeof(MPPacketHeader)], packet, len);

    if (IsHost && ((type & 0xFFFF) == 2))
    {
        // Host broadcasts client packets to all clients (Azahar-style)
        BroadcastPacketToAllClients((u8*)enetpacket->data, enetpacket->dataLength);
    }
    else if (((type & 0xFFFF) == 2) && LastHostPeer)
    {
        enet_peer_send(LastHostPeer, Chan_MP, enetpacket);
    }
    else
    {
        enet_host_broadcast(Host, Chan_MP, enetpacket);
    }
    enet_host_flush(Host);

    return len;
}

int LAN::RecvPacketGeneric(u8* packet, bool block, u64* timestamp)
{
    if (!Host) return 0;

    ProcessLAN(block ? 2 : 1);
    if (RXQueue.empty()) return 0;

    ENetPacket* enetpacket = RXQueue.front();
    RXQueue.pop();
    MPPacketHeader* header = (MPPacketHeader*)&enetpacket->data[0];

    u32 len = header->Length;
    if (len)
    {
        if (len > 2048) len = 2048;

        memcpy(packet, &enetpacket->data[sizeof(MPPacketHeader)], len);

        if (header->Type == 1)
        {
            LastHostID = header->SenderID;
            LastHostPeer = (ENetPeer*)enetpacket->userData;
        }
    }

    if (timestamp) *timestamp = header->Timestamp;
    enet_packet_destroy(enetpacket);
    return len;
}


int LAN::SendPacket(int inst, u8* packet, int len, u64 timestamp)
{
    return SendPacketGeneric(0, packet, len, timestamp);
}

int LAN::RecvPacket(int inst, u8* packet, u64* timestamp)
{
    return RecvPacketGeneric(packet, false, timestamp);
}


int LAN::SendCmd(int inst, u8* packet, int len, u64 timestamp)
{
    return SendPacketGeneric(1, packet, len, timestamp);
}

int LAN::SendReply(int inst, u8* packet, int len, u64 timestamp, u16 aid)
{
    return SendPacketGeneric(2 | (aid<<16), packet, len, timestamp);
}

int LAN::SendAck(int inst, u8* packet, int len, u64 timestamp)
{
    return SendPacketGeneric(3, packet, len, timestamp);
}

int LAN::RecvHostPacket(int inst, u8* packet, u64* timestamp)
{
    if (LastHostID != -1)
    {
        // check if the host is still connected

        if (!(ConnectedBitmask & (1<<LastHostID)))
            return -1;
    }

    return RecvPacketGeneric(packet, true, timestamp);
}

u16 LAN::RecvReplies(int inst, u8* packets, u64 timestamp, u16 aidmask)
{
    if (!Host) return 0;

    u16 ret = 0;
    u16 myinstmask = 1 << MyPlayer.ID;

    if ((myinstmask & ConnectedBitmask) == ConnectedBitmask)
        return 0;

    for (;;)
    {
        ProcessLAN(2);
        if (RXQueue.empty())
        {
            // no more replies available
            return ret;
        }

        ENetPacket* enetpacket = RXQueue.front();
        RXQueue.pop();
        MPPacketHeader* header = (MPPacketHeader*)&enetpacket->data[0];
        bool good = true;
        if ((header->Type & 0xFFFF) != 2)
            good = false;
        else if (header->Timestamp < (timestamp - 32))
            good = false;

        if (good)
        {
            u32 len = header->Length;
            if (len)
            {
                if (len > 1024) len = 1024;

                u32 aid = header->Type >> 16;
                memcpy(&packets[(aid-1)*1024], &enetpacket->data[sizeof(MPPacketHeader)], len);

                ret |= (1<<aid);
            }

            myinstmask |= (1<<header->SenderID);
            if (((myinstmask & ConnectedBitmask) == ConnectedBitmask) ||
                ((ret & aidmask) == aidmask))
            {
                // all the clients have sent their reply
                enet_packet_destroy(enetpacket);
                return ret;
            }
        }

        enet_packet_destroy(enetpacket);
    }
    return 0;
}

void LAN::BroadcastNodeMap()
{
    if (!Active || !IsHost) return;
    
    // Create node map packet similar to Azahar
    u8 cmd[1 + 16 * 7]; // cmd(1) + 16 nodes * (mac(6) + node_id(1))
    cmd[0] = Cmd_NodeMap;
    
    int offset = 1;
    Platform::Mutex_Lock(PlayersMutex);
    
    int num_entries = 0;
    for (int i = 0; i < 16; i++)
    {
        if (Players[i].Status != Player_None && Players[i].Status != Player_Disconnected)
        {
            // Write MAC address (6 bytes)
            u32 addr = Players[i].Address;
            cmd[offset++] = (u8)(addr >> 24);
            cmd[offset++] = (u8)(addr >> 16);
            cmd[offset++] = (u8)(addr >> 8);
            cmd[offset++] = (u8)(addr);
            cmd[offset++] = 0; // Padding
            cmd[offset++] = 0; // Padding
            
            // Write node ID (1 byte)
            cmd[offset++] = (u8)Players[i].ID;
            
            num_entries++;
        }
    }
    
    Platform::Mutex_Unlock(PlayersMutex);
    
    // Update packet length with actual number of entries
    u8 node_map[1 + num_entries * 7];
    node_map[0] = Cmd_NodeMap;
    memcpy(&node_map[1], &cmd[1], num_entries * 7);
    
    ENetPacket* pkt = enet_packet_create(node_map, 1 + num_entries * 7, ENET_PACKET_FLAG_RELIABLE);
    enet_host_broadcast(Host, Chan_Cmd, pkt);
    Platform::Log(Platform::LogLevel::Info, "LAN: broadcast node map with %d entries\n", num_entries);
}

void LAN::HandleNodeMapPacket(u8* data, int len)
{
    Platform::Log(Platform::LogLevel::Info, "LAN: HandleNodeMapPacket called with %d bytes, Active=%d, IsHost=%d\n", len, Active, IsHost);
    if (!Active || IsHost) return; // Host ignores node map packets
    
    Platform::Mutex_Lock(PlayersMutex);
    
    // Don't clear existing connections, just update/add new ones
    
    // Parse node map entries
    int num_entries = (len - 1) / 7; // Each entry: mac(6) + node_id(1)
    int offset = 1;
    
    for (int i = 0; i < num_entries && offset + 7 <= len; i++)
    {
        // Read MAC address
        u32 addr = (data[offset] << 24) | (data[offset+1] << 16) | (data[offset+2] << 8) | data[offset+3];
        offset += 6; // Skip MAC + padding
        
        // Read node ID
        u8 node_id = data[offset++];
        
        if (node_id >= 16) continue;
        if (node_id == MyPlayer.ID) continue;
        
        Players[node_id].ID = node_id;
        Players[node_id].Address = addr;
        Players[node_id].Status = Player_Client;
        
        // Connect to this peer if not already connected
        if (!RemotePeers[node_id])
        {
            char addrStr[16];
            snprintf(addrStr, 16, "%d.%d.%d.%d", (addr>>24), ((addr>>16)&0xFF), ((addr>>8)&0xFF), (addr&0xFF));
            
            Platform::Log(Platform::LogLevel::Info, "LAN: client connecting to peer %d at %s\n", node_id, addrStr);
            
            ENetAddress peeraddr;
            peeraddr.host = addr;
            peeraddr.port = kLANPort;
            ENetPeer* peer = enet_host_connect(Host, &peeraddr, 3, 0);
            if (peer)
            {
                RemotePeers[node_id] = peer;
                peer->data = &Players[node_id];
            }
        }
    }
    
    Platform::Mutex_Unlock(PlayersMutex);
    Platform::Log(Platform::LogLevel::Info, "LAN: processed node map with %d entries\n", num_entries);
}

void LAN::BroadcastPacketToAllClients(u8* data, int len)
{
    if (!Active || !IsHost) return;
    
    ENetPacket* pkt = enet_packet_create(data, len, ENET_PACKET_FLAG_RELIABLE);
    enet_host_broadcast(Host, Chan_MP, pkt);
    Platform::Log(Platform::LogLevel::Info, "LAN: broadcast packet to all clients\n");
}

}
