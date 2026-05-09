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

#ifndef LAN_H
#define LAN_H

#include <string>
#include <vector>
#include <map>
#include <queue>

#include <enet/enet.h>

#ifndef socket_t
    #ifdef __WIN32__
        #include <winsock2.h>
        #define socket_t    SOCKET
    #else
        #define socket_t    int
    #endif
#endif

#include "types.h"
#include "Platform.h"
#include "MPInterface.h"

namespace melonDS
{

class LAN : public MPInterface
{
public:
    LAN() noexcept;
    LAN(const LAN&) = delete;
    LAN& operator=(const LAN&) = delete;
    LAN(LAN&& other) = delete;
    LAN& operator=(LAN&& other) = delete;
    ~LAN() noexcept;

    enum PlayerStatus
    {
        Player_None = 0,        // no player in this entry
        Player_Client,          // game client
        Player_Host,            // game host
        Player_Connecting,      // player still connecting
        Player_Disconnected,    // player disconnected
    };

    struct Player
    {
        int ID;
        char Name[32];
        PlayerStatus Status;
        u32 Address;

        bool IsLocalPlayer;
        u32 Ping;
    };

    struct RoomInfo
    {
        char RoomCode[9];       // 8-char room code + NUL
        char RoomName[64];      // Human-readable room name
        char GameName[64];      // Game being played
        char Description[128];  // Room description
        char Password[33];       // Room password (32 + NUL), empty if no password
        u8 NumPlayers;
        u8 MaxPlayers;
        u8 HasPassword;         // 1 if password protected
        u8 InGame;              // 1 if game is running
        u32 HostID;
    };

    struct DiscoveryData
    {
        u32 Magic;
        u32 Version;
        u32 Tick;
        RoomInfo Room;
    };

    struct ChatMessage
    {
        u32 SenderID;
        char Message[256];
        u64 Timestamp;
    };

    bool StartDiscovery();
    void EndDiscovery();
    bool StartHost(const char* player, int numplayers, const char* roomName, const char* password = nullptr);
    bool StartClient(const char* player, const char* host, const char* password = nullptr);
    void EndSession();

    std::map<u32, DiscoveryData> GetDiscoveryList();
    std::vector<Player> GetPlayerList();
    RoomInfo GetRoomInfo();
    int GetNumPlayers() { return NumPlayers; }
    int GetMaxPlayers() { return MaxPlayers; }

    // Chat
    void SendChatMessage(const char* message);
    std::vector<ChatMessage> GetChatMessages();

    // Moderation (host only)
    bool KickPlayer(int playerID);
    bool BanPlayer(int playerID);

    // P2P methods
    bool ConnectToPeer(int playerID, const char* address);
    void DisconnectFromPeer(int playerID);
    void BroadcastPeerList();
    
    // Azahar-style broadcast methods
    void BroadcastNodeMap();
    void HandleNodeMapPacket(u8* data, int len);
    void BroadcastPacketToAllClients(u8* data, int len);

    void Process() override;

    void Begin(int inst) override;
    void End(int inst) override;

    int SendPacket(int inst, u8* data, int len, u64 timestamp) override;
    int RecvPacket(int inst, u8* data, u64* timestamp) override;
    int SendCmd(int inst, u8* data, int len, u64 timestamp) override;
    int SendReply(int inst, u8* data, int len, u64 timestamp, u16 aid) override;
    int SendAck(int inst, u8* data, int len, u64 timestamp) override;
    int RecvHostPacket(int inst, u8* data, u64* timestamp) override;
    u16 RecvReplies(int inst, u8* data, u64 timestamp, u16 aidmask) override;

private:
    bool Inited;
    bool Active;
    bool IsHost;

    ENetHost* Host;
    ENetPeer* RemotePeers[16];

    socket_t DiscoverySocket;
    u32 DiscoveryLastTick;
    std::map<u32, DiscoveryData> DiscoveryList;
    Platform::Mutex* DiscoveryMutex;

    Player Players[16];
    int NumPlayers;
    int MaxPlayers;
    Platform::Mutex* PlayersMutex;

    RoomInfo CurrentRoom;
    Platform::Mutex* RoomMutex;

    std::vector<ChatMessage> ChatHistory;
    Platform::Mutex* ChatMutex;

    Player MyPlayer;
    u32 HostAddress;

    u16 ConnectedBitmask;

    int MPRecvTimeout;
    int LastHostID;
    ENetPeer* LastHostPeer;
    std::queue<ENetPacket*> RXQueue;

    u32 FrameCount;

    void ProcessDiscovery();

    void HostUpdatePlayerList();
    void HostUpdateRoomInfo();
    void ClientUpdatePlayerList();

    void ProcessHostEvent(ENetEvent& event);
    void ProcessClientEvent(ENetEvent& event);
    void ProcessEvent(ENetEvent& event);
    void ProcessLAN(int type);

    int SendPacketGeneric(u32 type, u8* packet, int len, u64 timestamp);
    int RecvPacketGeneric(u8* packet, bool block, u64* timestamp);

    bool VerifyPassword(const char* password);
};

}

#endif // LAN_H
