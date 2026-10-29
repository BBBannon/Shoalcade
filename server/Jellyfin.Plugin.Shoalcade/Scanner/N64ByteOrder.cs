using System;
using System.Buffers.Binary;
using System.IO;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>The three ways an N64 ROM dump is stored.</summary>
public enum N64Order
{
    /// <summary>Not an N64 ROM header we know (hashed as it is).</summary>
    Unknown,

    /// <summary>Big-endian, the cartridge's own order (.z64): starts <c>80 37 12 40</c>.</summary>
    Z64,

    /// <summary>Each 16-bit pair swapped (.v64, Doctor V64): starts <c>37 80 40 12</c>.</summary>
    V64,

    /// <summary>Each 32-bit word reversed (.n64, little-endian): starts <c>40 12 37 80</c>.</summary>
    N64,
}

/// <summary>
/// N64 byte orders (D146). No-Intro and libretro-database list the MD5 of the big-endian (.z64) order, so a .v64 or
/// .n64 dump is hashed after turning it into .z64 order: all three get the same database name and box art. The order
/// is read from the first four bytes (the cartridge header's first word, 0x80371240), never from the file name.
/// </summary>
public static class N64ByteOrder
{
    /// <summary>File extensions of N64 ROMs (the only files whose hash is ever converted).</summary>
    public static readonly string[] Extensions = [".z64", ".n64", ".v64"];

    /// <summary>Works out the byte order from a ROM's first four bytes.</summary>
    /// <param name="head">At least the first four bytes.</param>
    /// <returns>The order, or <see cref="N64Order.Unknown"/>.</returns>
    public static N64Order Detect(ReadOnlySpan<byte> head)
    {
        if (head.Length < 4)
        {
            return N64Order.Unknown;
        }

        return BinaryPrimitives.ReadUInt32BigEndian(head) switch
        {
            0x80371240 => N64Order.Z64,
            0x37804012 => N64Order.V64,
            0x40123780 => N64Order.N64,
            _ => N64Order.Unknown,
        };
    }

    /// <summary>Turns bytes in <paramref name="order"/> into .z64 order, in place. Only whole 2- or 4-byte units are
    /// moved; a few odd bytes at the very end (never in a real dump) stay as they are.</summary>
    /// <param name="data">Bytes from the start of the ROM, or any later piece starting at a multiple of 4.</param>
    /// <param name="order">Byte order of the data.</param>
    public static void ToZ64(Span<byte> data, N64Order order)
    {
        if (order == N64Order.V64)
        {
            for (var i = 0; i + 1 < data.Length; i += 2)
            {
                (data[i], data[i + 1]) = (data[i + 1], data[i]);
            }
        }
        else if (order == N64Order.N64)
        {
            for (var i = 0; i + 3 < data.Length; i += 4)
            {
                (data[i], data[i + 1], data[i + 2], data[i + 3]) = (data[i + 3], data[i + 2], data[i + 1], data[i]);
            }
        }
    }

    /// <summary>True if a file name has an N64 ROM extension.</summary>
    /// <param name="name">File or zip entry name.</param>
    /// <returns>Whether it is an N64 ROM name.</returns>
    public static bool IsN64Name(string? name) =>
        !string.IsNullOrEmpty(name) && Array.Exists(Extensions, e => string.Equals(e, Path.GetExtension(name), StringComparison.OrdinalIgnoreCase));
}
