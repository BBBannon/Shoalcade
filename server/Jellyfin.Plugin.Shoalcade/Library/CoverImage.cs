using System;
using System.Buffers.Binary;
using Jellyfin.Plugin.Shoalcade.Scanner;

namespace Jellyfin.Plugin.Shoalcade.Library;

/// <summary>A checked cover picture: what it is and how big.</summary>
/// <param name="MediaType">image/png, image/jpeg or image/webp.</param>
/// <param name="Extension">.png, .jpg or .webp.</param>
/// <param name="Width">Width in pixels.</param>
/// <param name="Height">Height in pixels.</param>
public sealed record CoverInfo(string MediaType, string Extension, int Width, int Height);

/// <summary>
/// Checks a picture an admin gives a game (upload or a picked libretro cover, D122) before it is stored. Same rule as
/// box art (G5 s0.2 L2): PNG, JPEG or WebP only, judged by the file's own bytes (never by its name or the browser's
/// word), at most 10 MB; and the picture's header must give a sensible size, so a file that only starts like a picture
/// is refused. SVG, HTML and anything else never become a game's picture.
/// </summary>
public static class CoverImage
{
    /// <summary>Largest picture accepted (same as box art).</summary>
    public const long MaxBytes = BoxArtProvider.MaxImageBytes;

    /// <summary>Smallest side accepted, in pixels.</summary>
    public const int MinSide = 16;

    /// <summary>
    /// Largest side accepted, in pixels (G5 dev 2 L2: was 10000, which costs ~400 MB to decode for a tile; box art never
    /// needs more than 4096). Applies to uploads and to "Find a cover" previews and picks alike.
    /// </summary>
    public const int MaxSide = 4096;

    /// <summary>Checks the bytes.</summary>
    /// <param name="data">The picture.</param>
    /// <param name="info">What it is, if fine.</param>
    /// <returns>Null if fine, else the reason (plain English, shown to the admin).</returns>
    public static string? Check(ReadOnlySpan<byte> data, out CoverInfo? info)
    {
        info = null;
        if (data.Length == 0)
        {
            return "That file is empty.";
        }

        if (data.Length > MaxBytes)
        {
            return "That picture is too big (10 MB at most).";
        }

        var type = BoxArtProvider.PictureType(data);
        if (type is null)
        {
            return "That isn't a PNG, JPEG or WebP picture.";
        }

        var size = type switch
        {
            "image/png" => PngSize(data),
            "image/jpeg" => JpegSize(data),
            _ => WebpSize(data),
        };
        if (size is not (int w, int h))
        {
            return "That picture looks damaged, so it can't be used.";
        }

        if (w < MinSide || h < MinSide || w > MaxSide || h > MaxSide)
        {
            return $"That picture is {w} × {h} pixels. Use one between {MinSide} and {MaxSide} pixels on each side.";
        }

        info = new CoverInfo(type, type switch { "image/png" => ".png", "image/jpeg" => ".jpg", _ => ".webp" }, w, h);
        return null;
    }

    // PNG: the first chunk must be IHDR, holding width and height.
    private static (int, int)? PngSize(ReadOnlySpan<byte> d)
    {
        if (d.Length < 33 || !d.Slice(12, 4).SequenceEqual("IHDR"u8) || BinaryPrimitives.ReadUInt32BigEndian(d.Slice(8, 4)) != 13)
        {
            return null;
        }

        var w = BinaryPrimitives.ReadUInt32BigEndian(d.Slice(16, 4));
        var h = BinaryPrimitives.ReadUInt32BigEndian(d.Slice(20, 4));
        return w > int.MaxValue || h > int.MaxValue ? null : ((int)w, (int)h);
    }

    // JPEG: walk the markers to the first start-of-frame (SOF0-SOF15, not DHT/JPG/DAC), which holds the size.
    private static (int, int)? JpegSize(ReadOnlySpan<byte> d)
    {
        var i = 2;
        while (i + 4 <= d.Length)
        {
            if (d[i] != 0xFF)
            {
                return null;
            }

            var marker = d[i + 1];
            if (marker == 0xFF)
            {
                i++; // fill byte
                continue;
            }

            if (marker is 0xD8 or 0x01 || marker is >= 0xD0 and <= 0xD7)
            {
                i += 2; // markers with no length
                continue;
            }

            if (marker is 0xD9 or 0xDA)
            {
                return null; // end of image or image data before any frame header
            }

            var len = BinaryPrimitives.ReadUInt16BigEndian(d.Slice(i + 2, 2));
            if (len < 2)
            {
                return null;
            }

            if (marker is >= 0xC0 and <= 0xCF and not 0xC4 and not 0xC8 and not 0xCC)
            {
                if (i + 9 > d.Length)
                {
                    return null;
                }

                int h = BinaryPrimitives.ReadUInt16BigEndian(d.Slice(i + 5, 2));
                int w = BinaryPrimitives.ReadUInt16BigEndian(d.Slice(i + 7, 2));
                return (w, h);
            }

            i += 2 + len;
        }

        return null;
    }

    // WebP: RIFF....WEBP then a VP8 (lossy), VP8L (lossless) or VP8X (extended) chunk.
    private static (int, int)? WebpSize(ReadOnlySpan<byte> d)
    {
        if (d.Length < 30)
        {
            return null;
        }

        var chunk = d.Slice(12, 4);
        if (chunk.SequenceEqual("VP8 "u8))
        {
            // Frame tag (3 bytes), start code 9D 01 2A, then 14-bit width and height.
            if (d[23] != 0x9D || d[24] != 0x01 || d[25] != 0x2A)
            {
                return null;
            }

            return (BinaryPrimitives.ReadUInt16LittleEndian(d.Slice(26, 2)) & 0x3FFF, BinaryPrimitives.ReadUInt16LittleEndian(d.Slice(28, 2)) & 0x3FFF);
        }

        if (chunk.SequenceEqual("VP8L"u8))
        {
            if (d[20] != 0x2F)
            {
                return null;
            }

            var bits = BinaryPrimitives.ReadUInt32LittleEndian(d.Slice(21, 4));
            return ((int)(bits & 0x3FFF) + 1, (int)((bits >> 14) & 0x3FFF) + 1);
        }

        if (chunk.SequenceEqual("VP8X"u8))
        {
            return ((d[24] | (d[25] << 8) | (d[26] << 16)) + 1, (d[27] | (d[28] << 8) | (d[29] << 16)) + 1);
        }

        return null;
    }
}
