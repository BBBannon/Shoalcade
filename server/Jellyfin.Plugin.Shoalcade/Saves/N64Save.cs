using System;
using System.Buffers.Binary;

namespace Jellyfin.Plugin.Shoalcade.Saves;

/// <summary>
/// Nintendo 64 save rules (D146). The N64 save is Mupen64Plus-Next's whole save RAM: EEPROM, four Controller Paks, SRAM
/// and FlashRAM in one 296,960-byte block, the same bytes in the app and the browser player. The core formats the
/// Controller Paks by itself when a game starts with no save, so an untouched block is NOT all one byte and the plain
/// blank check would take it for a real save. <see cref="IsUntouched"/> catches it. Same rule as the app's
/// <c>N64Save.isUntouched</c> and the test-bed's <c>makeroms.n64_untouched</c>; tested against <c>tools/testbed/n64_saves/</c>.
/// </summary>
public static class N64Save
{
    /// <summary>Size of the whole save block in bytes (0x48800).</summary>
    public const int Size = 0x48800;

    private const int PakSize = 0x8000;
    private const int Block = 32;

    /// <summary>
    /// True for the core's own fresh block: exactly <see cref="Size"/> bytes; EEPROM (0x0-0x800), SRAM (0x20800-0x28800)
    /// and FlashRAM (0x28800-0x48800) all 0xFF; and each of the four Controller Paks (0x800, 0x8800, 0x10800, 0x18800)
    /// exactly as the core's <c>format_mempak</c> leaves it, with any serial (the core picks a random one after the first
    /// game in a process). Anything else is a real save.
    /// </summary>
    /// <param name="data">Save bytes.</param>
    /// <returns>Whether the save is untouched.</returns>
    public static bool IsUntouched(ReadOnlySpan<byte> data)
    {
        if (data.Length != Size
            || data[..0x800].ContainsAnyExcept((byte)0xFF)
            || data[0x20800..].ContainsAnyExcept((byte)0xFF))
        {
            return false;
        }

        for (var k = 0; k < 4; k++)
        {
            if (!IsFreshPak(data.Slice(0x800 + (k * PakSize), PakSize)))
            {
                return false;
            }
        }

        return true;
    }

    /// <summary>
    /// A Controller Pak as <c>format_mempak</c> leaves it. Page 0: 32-byte blocks 0, 2, 5, 7 zero; blocks 1, 3, 4, 6 the
    /// same ID block (24-byte serial, device 00 01, banks 01, version 00, sum = big-endian 16-bit sum of words 0-13,
    /// isum = 0xFFF2 - sum). Pages 1 and 2: the index table (00 71, eight 00, then 00 03 x 123). Pages 3-127: zero.
    /// </summary>
    private static bool IsFreshPak(ReadOnlySpan<byte> pak)
    {
        var id = pak.Slice(Block, Block);
        foreach (var i in (ReadOnlySpan<int>)[0, 2, 5, 7])
        {
            if (pak.Slice(i * Block, Block).ContainsAnyExcept((byte)0))
            {
                return false;
            }
        }

        foreach (var i in (ReadOnlySpan<int>)[3, 4, 6])
        {
            if (!pak.Slice(i * Block, Block).SequenceEqual(id))
            {
                return false;
            }
        }

        if (id[24] != 0 || id[25] != 1 || id[26] != 1 || id[27] != 0)
        {
            return false;
        }

        var sum = 0;
        for (var w = 0; w < 14; w++)
        {
            sum += BinaryPrimitives.ReadUInt16BigEndian(id[(w * 2)..]);
        }

        sum &= 0xFFFF;
        if (BinaryPrimitives.ReadUInt16BigEndian(id[28..]) != sum
            || BinaryPrimitives.ReadUInt16BigEndian(id[30..]) != ((0xFFF2 - sum) & 0xFFFF))
        {
            return false;
        }

        for (var page = 1; page <= 2; page++)
        {
            var t = pak.Slice(page * 256, 256);
            if (t[0] != 0x00 || t[1] != 0x71 || t[2..10].ContainsAnyExcept((byte)0))
            {
                return false;
            }

            for (var e = 10; e < 256; e += 2)
            {
                if (t[e] != 0x00 || t[e + 1] != 0x03)
                {
                    return false;
                }
            }
        }

        return !pak[768..].ContainsAnyExcept((byte)0);
    }
}
