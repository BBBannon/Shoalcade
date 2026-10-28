using System;

namespace Jellyfin.Plugin.Shoalcade.Saves;

/// <summary>
/// PS1 memory card rules (D137). The PS1 save is memory card 1: a raw 128 KB card image, the same bytes in the app and
/// the browser player. PCSX ReARMed formats an empty card by itself when it has none, so an untouched card is NOT all
/// one byte and the plain blank check would take it for a real save. <see cref="IsEmptyCard"/> catches it. Same rule as
/// the app's <c>PsxCard.isEmptyCard</c>; both are tested against <c>tools/testbed/psx_cards/</c>.
/// </summary>
public static class PsxCard
{
    /// <summary>Size of a memory card image in bytes.</summary>
    public const int Size = 131072;

    private const int Frame = 128;
    private const byte Free = 0xA0;

    /// <summary>
    /// True for a formatted card with nothing saved on it: exactly <see cref="Size"/> bytes, frame 0 starts "MC", frames
    /// 0-15 (header + directory) each have a valid XOR checksum in their last byte, and all 15 directory entries are free.
    /// Anything else (other sizes, damaged or used cards, other data that happens to start "MC") is not empty.
    /// </summary>
    /// <param name="data">Card bytes.</param>
    /// <returns>Whether the card is empty.</returns>
    public static bool IsEmptyCard(ReadOnlySpan<byte> data)
    {
        if (data.Length != Size || data[0] != (byte)'M' || data[1] != (byte)'C')
        {
            return false;
        }

        for (var f = 0; f < 16; f++)
        {
            var frame = data.Slice(f * Frame, Frame);
            byte x = 0;
            foreach (var b in frame[..^1])
            {
                x ^= b;
            }

            if (x != frame[^1] || (f > 0 && frame[0] != Free))
            {
                return false;
            }
        }

        return true;
    }
}
