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

#include "CartRetailBT.h"
#include "../NDS.h"
#include "../Utils.h"

// CartRetailBT: NDS cartridge with Bluetooth transceiver (ie. Pokémon Typing Adventure)
// the BT transceiver is connected to the SPI interface, and replaces regular SRAM

namespace melonDS
{
using Platform::Log;
using Platform::LogLevel;

namespace NDSCart
{

CartRetailBT::CartRetailBT(const u8* rom, u32 len, u32 chipid, ROMListEntry romparams, std::unique_ptr<u8[]>&& sram, u32 sramlen, void* userdata) :
    CartRetailBT(CopyToUnique(rom, len), len, chipid, romparams, std::move(sram), sramlen, userdata)
{
}

CartRetailBT::CartRetailBT(std::unique_ptr<u8[]>&& rom, u32 len, u32 chipid, ROMListEntry romparams, std::unique_ptr<u8[]>&& sram, u32 sramlen, void* userdata) :
    CartRetail(std::move(rom), len, chipid, false, romparams, std::move(sram), sramlen, userdata, CartType::RetailBT)
{
    Log(LogLevel::Info,"POKETYPE CART\n");
}

CartRetailBT::~CartRetailBT() = default;

u8 CartRetailBT::SPITransmitReceive(u8 val)
{
    //Log(LogLevel::Debug,"POKETYPE SPI: %02X %d %d - %08X\n", val, pos, last, NDS::GetPC(0));

    static u8 pos = 0;
    static u8 cmd = 0;
    static u8 status = 0x02; // Ready status

    // First byte is command
    if (pos == 0)
    {
        cmd = val;
        pos++;

        // If this is a status check command, reset position after response
        if (val == 0xFF || val == 0x00)
        {
            pos = 0;
            return status;
        }

        return 0x00; // Acknowledge command
    }

    // Handle different commands
    switch (cmd)
    {
        case 0x01: // Read status
            pos = 0;
            return status;

        case 0x02: // Get device ID
            if (pos < 4)
            {
                pos++;
                return 0x00; // Return device ID bytes
            }
            pos = 0;
            return 0x00;

        case 0x05: // Data transfer - return success
            pos = 0;
            return 0x00;

        case 0x06: // Check connection status
            pos = 0;
            return 0x01; // Connected

        default:
            // Unknown command - return 0 and reset
            pos = 0;
            return 0x00;
    }

    // Reset position if we get here unexpectedly
    pos = 0;
    return 0x00;
}


}

}
