using System;
using System.Collections.Generic;
using System.Text;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>One <c>game ( ... )</c> block of a libretro-database .dat file.</summary>
/// <param name="Fields">The game's own fields (name, region, releaseyear, developer, comment...), keys lower case.</param>
/// <param name="Roms">Each <c>rom ( ... )</c> inside it (name, size, crc, md5, sha1...), keys lower case.</param>
public sealed record DatGameBlock(IReadOnlyDictionary<string, string> Fields, IReadOnlyList<IReadOnlyDictionary<string, string>> Roms);

/// <summary>
/// Reads the clrmamepro text format used by libretro-database (<c>game ( name "..." rom ( crc ... md5 ... ) )</c>).
/// Forgiving: anything it doesn't understand is skipped, never thrown.
/// </summary>
public static class DatFile
{
    /// <summary>Deepest block nesting read (real DATs nest 2 deep). Deeper blocks are skipped without recursion, so a
    /// hostile DAT can't overflow the stack and take Jellyfin down (G5 s0.2 L1).</summary>
    public const int MaxDepth = 8;

    /// <summary>Every game block in a .dat file's text (the <c>clrmamepro ( ... )</c> header is skipped).</summary>
    /// <param name="text">File contents.</param>
    /// <returns>Game blocks, in file order.</returns>
    public static IReadOnlyList<DatGameBlock> Parse(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        var tokens = Tokenise(text);
        var games = new List<DatGameBlock>();
        var i = 0;
        while (i < tokens.Count)
        {
            var t = tokens[i];
            if (!t.Quoted && t.Text != "(" && t.Text != ")" && i + 1 < tokens.Count && tokens[i + 1].Text == "(" && !tokens[i + 1].Quoted)
            {
                var isGame = string.Equals(t.Text, "game", StringComparison.OrdinalIgnoreCase);
                i += 2;
                var block = ReadBlock(tokens, ref i, 1);
                if (isGame)
                {
                    games.Add(block);
                }

                continue;
            }

            i++;
        }

        return games;
    }

    // Reads key/value pairs up to the matching ")". Nested blocks named "rom" are collected; other nested blocks skipped.
    private static DatGameBlock ReadBlock(List<Token> tokens, ref int i, int depth)
    {
        var fields = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var roms = new List<IReadOnlyDictionary<string, string>>();
        while (i < tokens.Count)
        {
            var key = tokens[i];
            if (key.Text == ")" && !key.Quoted)
            {
                i++;
                break;
            }

            if (i + 1 >= tokens.Count)
            {
                i++;
                break;
            }

            var value = tokens[i + 1];
            if (value.Text == "(" && !value.Quoted)
            {
                i += 2;
                if (depth >= MaxDepth)
                {
                    SkipBlock(tokens, ref i);
                    continue;
                }

                var inner = ReadBlock(tokens, ref i, depth + 1);
                if (string.Equals(key.Text, "rom", StringComparison.OrdinalIgnoreCase))
                {
                    roms.Add(inner.Fields);
                }

                continue;
            }

            if (value.Text == ")" && !value.Quoted)
            {
                i++; // a key with no value: drop it, let the ")" close the block
                continue;
            }

            fields.TryAdd(key.Text.ToLowerInvariant(), value.Text);
            i += 2;
        }

        return new DatGameBlock(fields, roms);
    }

    // Skips to just after the ")" matching an already-read "(", with a counter instead of recursion.
    private static void SkipBlock(List<Token> tokens, ref int i)
    {
        var open = 1;
        while (i < tokens.Count && open > 0)
        {
            if (!tokens[i].Quoted)
            {
                open += tokens[i].Text switch { "(" => 1, ")" => -1, _ => 0 };
            }

            i++;
        }
    }

    private static List<Token> Tokenise(string text)
    {
        var tokens = new List<Token>();
        var i = 0;
        var sb = new StringBuilder();
        while (i < text.Length)
        {
            var c = text[i];
            if (char.IsWhiteSpace(c))
            {
                i++;
            }
            else if (c is '(' or ')')
            {
                tokens.Add(new Token(c.ToString(), false));
                i++;
            }
            else if (c == '"')
            {
                sb.Clear();
                i++;
                while (i < text.Length && text[i] != '"')
                {
                    sb.Append(text[i]); // no escapes in this format: names never contain a double quote
                    i++;
                }

                i++; // closing quote
                tokens.Add(new Token(sb.ToString(), true));
            }
            else
            {
                var start = i;
                while (i < text.Length && !char.IsWhiteSpace(text[i]) && text[i] is not '(' and not ')' and not '"')
                {
                    i++;
                }

                tokens.Add(new Token(text[start..i], false));
            }
        }

        return tokens;
    }

    private readonly record struct Token(string Text, bool Quoted);
}
