# Internet link play (through your Jellyfin)

**Trade between two phones anywhere, through your own Jellyfin.** Game Boy Advance only for now. Local Wi-Fi link play is unchanged and still the fastest.

**The Windows app works too:** PC ↔ phone and PC ↔ PC, with the same buttons (**Host over the internet** / **Join over the internet**). Wherever this guide says "phone", a PC can take its place.

---

## What you need

- Both phones logged in to the **same Jellyfin** (two users, or one user on two phones)
- A Shoalcade plugin that says it can do it (the app checks: no setting to turn on)
- Each Jellyfin user allowed **remote access** (Dashboard → Users → the user → *Allow remote connections to this server*)
- The usual GBA link rules: a 128 KB save (Pokémon Gen 3 is fine)

---

## How to link

1. Both phones: library **⋮ → Link play** on the same game.
2. **Phone A:** tap **Host over the internet**. It shows a **Room code** (6 letters, e.g. `K7Q M2P`) and a **PIN**.
3. **Phone B:** tap **Join over the internet**, type the room code and the PIN, tap **Connect**.
4. Both games start linked.

- **Link cable mode** (Ruby/Sapphire with Emerald/FireRed/LeafGreen, Emerald hacks like Monomyth): tick it on the host, as on Wi-Fi.
- **Slower than Wi-Fi:** expect trades to take a few seconds longer on mobile data. Battles may stutter.
- **A room waits 10 minutes** for the second phone, then closes.

---

## Nginx Proxy Manager (NPM)

**Usually nothing to change.** Internet link play is plain HTTPS: one long download plus small uploads, **no WebSockets**.

| Setting | What it needs | Why |
|---|---|---|
| **Websockets Support** | Not needed (leave as it is) | The link doesn't use WebSockets |
| **Response buffering** | Nothing to do | The plugin sends `X-Accel-Buffering: no`, which nginx obeys per answer |
| **`proxy_read_timeout`** | Default (60 s) is fine | The plugin sends a heartbeat every 15 s |
| **Request size** | Default (1 MB) is fine | Uploads are at most 64 KB |
| **HTTP/2** | Either | |

### Only if links stall or drop every ~60 s

That means a custom setting on the Jellyfin host is overriding the above. In NPM: **Proxy Hosts → your Jellyfin host → ⋮ Edit → Advanced → Custom Nginx Configuration**, add:

```nginx
location /shoalcade/link/ {
    proxy_pass $forward_scheme://$server:$port;
    proxy_http_version 1.1;
    proxy_buffering off;
    proxy_request_buffering off;
    proxy_read_timeout 120s;
    proxy_send_timeout 120s;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

- **Signs you need it:** the host shows the PIN but the joiner never connects, or a link drops at the same point every minute.
- **Also check:** `proxy_ignore_headers X-Accel-Buffering;` anywhere in your NPM config turns the plugin's header off. Remove it for this path.
- **Jellyfin on a base URL** (e.g. `/jellyfin`)? Put it in front: `location /jellyfin/shoalcade/link/`.
- **Jellyfin log:** with *Slow response warning* on (Dashboard → Logs), Jellyfin may log a "slow response" line for `/shoalcade/link/down` when a link ends. That's the long download, not a problem; it never contains link data.
- **Another proxy or CDN in front** (e.g. Cloudflare) and links stall? Try without it: some proxies hold back streamed answers.

---

## Privacy and safety

- **Only your Jellyfin users** can open or join a room. Every call needs a Jellyfin login.
- **The PIN never leaves the phones.** The phones prove they both know it (D133), so even the server can't link a stranger in.
- **Nothing is stored.** Link data passes through the server's memory only, is never written to disk and never logged.
- **Limits:** 1 room per user, 2 phones per room, 16 rooms per server, and rate limits on room codes, so codes can't be guessed.
