/*
 * ShoalcadePlayer: link play (part of Shoalcade). See link.h; the logic is linkshim.c's, line for line where it can be.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "link.h"

#include <chrono>
#include <cstring>

#include "log.h"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
typedef SOCKET sock_t;
#define SC_BAD_SOCK INVALID_SOCKET
static int sock_err() { return WSAGetLastError(); }
static bool would_block(int e) { return e == WSAEWOULDBLOCK || e == WSAEINTR; }
static bool in_progress(int e) { return e == WSAEWOULDBLOCK || e == WSAEINPROGRESS; }
static void close_sock(sock_t s) { closesocket(s); }
#define SC_SEND_FLAGS 0
#else
#include <arpa/inet.h>
#include <cerrno>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>
typedef int sock_t;
#define SC_BAD_SOCK (-1)
static int sock_err() { return errno; }
static bool would_block(int e) { return e == EAGAIN || e == EWOULDBLOCK || e == EINTR; }
static bool in_progress(int e) { return e == EINPROGRESS || e == EINTR; }
static void close_sock(sock_t s) { close(s); }
#define SC_SEND_FLAGS MSG_NOSIGNAL
#endif

namespace sc {
// ---------------------------------------------------------------------------------------------------------------
// Wire format

namespace linkwire {
bool encode(const void *buf, size_t len, std::vector<uint8_t> &out) {
    if (!buf || len == 0 || len > kMaxPacket) return false;
    uint32_t n = (uint32_t)len;
    uint8_t hdr[4] = {(uint8_t)(n >> 24), (uint8_t)(n >> 16), (uint8_t)(n >> 8), (uint8_t)n};
    out.insert(out.end(), hdr, hdr + 4);
    out.insert(out.end(), (const uint8_t *)buf, (const uint8_t *)buf + len);
    return true;
}

void Decoder::push(const void *data, size_t len) {
    if (off_ > 0 && off_ == buf_.size()) buf_.clear(), off_ = 0;
    else if (off_ > 256 * 1024) buf_.erase(buf_.begin(), buf_.begin() + (std::ptrdiff_t)off_), off_ = 0;
    buf_.insert(buf_.end(), (const uint8_t *)data, (const uint8_t *)data + len);
}

Decoder::Result Decoder::next(std::vector<uint8_t> &out) {
    size_t have = buf_.size() - off_;
    if (have < 4) return Result::NeedMore;
    const uint8_t *p = buf_.data() + off_;
    uint32_t len = (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
    if (len == 0 || len > kMaxPacket) return Result::Bad;
    if (have < 4 + (size_t)len) return Result::NeedMore;
    out.assign(p + 4, p + 4 + len);
    off_ += 4 + len;
    return Result::Packet;
}
} // namespace linkwire

// ---------------------------------------------------------------------------------------------------------------
// The core's callbacks: one link per process, as on the phone.

namespace {
Link *g_link = nullptr;
void RETRO_CALLCONV send_cb(int flags, const void *buf, size_t len, uint16_t client_id) {
    if (g_link) g_link->net_send(flags, buf, len, client_id);
}
void RETRO_CALLCONV poll_receive_cb() {
    if (g_link) g_link->net_poll_receive();
}
} // namespace

const char *Link::state_name(int s) {
    switch (s) {
    case WaitingCore: return "waiting";
    case Active: return "active";
    case Lost: return "lost";
    case Refused: return "refused";
    case NoSupport: return "noSupport";
    default: return "off";
    }
}

Link::Link() {
#ifdef _WIN32
    static bool wsa = [] {
        WSADATA d;
        return WSAStartup(MAKEWORD(2, 2), &d) == 0;
    }();
    (void)wsa;
#endif
    g_link = this;
}

Link::~Link() {
    close_socket();
    if (g_link == this) g_link = nullptr;
}

void Link::set_state(int s, const std::string &why) {
    if (s == state_ && why == why_) return;
    state_ = s;
    why_ = why;
    if (on_change) on_change();
}

void Link::close_socket() {
    if (sock_ != -1) close_sock((sock_t)sock_);
    sock_ = -1;
}

bool Link::open(const Config &c, std::string &err) {
    if (c.port <= 0 || c.port > 65535) return err = "link.port must be 1-65535", false;
    for (char ch : c.token)
        if ((unsigned char)ch < 0x21 || (unsigned char)ch > 0x7e) return err = "link.token must be printable ASCII with no spaces", false;
    if (c.token.size() > 256) return err = "link.token is too long (256 at most)", false;

    sock_t s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == SC_BAD_SOCK) return err = "couldn't make a socket (" + std::to_string(sock_err()) + ")", false;
#ifdef _WIN32
    u_long nb = 1;
    ioctlsocket(s, FIONBIO, &nb);
#else
    fcntl(s, F_SETFL, fcntl(s, F_GETFL, 0) | O_NONBLOCK);
#endif
    int one = 1;
    // Small packets many times a frame: never wait to batch them (Nagle).
    setsockopt(s, IPPROTO_TCP, TCP_NODELAY, (const char *)&one, sizeof one);

    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons((uint16_t)c.port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK); // only ever this PC: the shell does the real networking
    if (connect(s, (sockaddr *)&a, sizeof a) != 0) {
        int e = sock_err();
        if (!in_progress(e)) {
            close_sock(s);
            return err = "couldn't reach the link on 127.0.0.1:" + std::to_string(c.port) + " (" + std::to_string(e) + ")", false;
        }
        fd_set w, x;
        FD_ZERO(&w);
        FD_ZERO(&x);
        FD_SET(s, &w);
        FD_SET(s, &x);
        timeval tv{c.connect_timeout_ms / 1000, (c.connect_timeout_ms % 1000) * 1000};
        int r = select((int)s + 1, nullptr, &w, &x, &tv);
        int so = 0;
        socklen_t sl = sizeof so;
        if (r > 0) getsockopt(s, SOL_SOCKET, SO_ERROR, (char *)&so, &sl);
        if (r <= 0 || so != 0 || FD_ISSET(s, &x)) {
            close_sock(s);
            return err = "couldn't reach the link on 127.0.0.1:" + std::to_string(c.port) +
                         (r == 0 ? " (timed out)" : " (" + std::to_string(so) + ")"), false;
        }
    }
    sock_ = (intptr_t)s;
    host_ = c.host;
    self_id_ = host_ ? 0 : 1;
    peer_id_ = host_ ? 1 : 0;
    configured_ = true;
    started_ = in_poll_ = stop_pending_ = false;
    rx_ = tx_ = rx_bytes_ = tx_bytes_ = 0;
    out_.clear();
    if (!c.token.empty()) {
        // The shell reads this one line byte by byte (as LinkHandshake.readLine), then pumps packets.
        std::string line = c.token + "\n";
        out_.insert(out_.end(), line.begin(), line.end());
        auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(c.connect_timeout_ms);
        while (sock_ != -1 && !out_.empty() && std::chrono::steady_clock::now() < end) flush_out();
        if (sock_ == -1 || !out_.empty()) {
            close_socket();
            configured_ = false;
            return err = "the link closed before the player could say who it is", false;
        }
    }
    set_state(WaitingCore);
    LOGI("link configured: %s, 127.0.0.1:%d", host_ ? "host" : "join", c.port);
    return true;
}

void Link::set_interface(const retro_netpacket_callback *cb) {
    // Copied: melonDS DS passes a temporary.
    if (cb) {
        iface_ = *cb;
        have_iface_ = iface_.start != nullptr && iface_.receive != nullptr;
        proto_ = iface_.protocol_version ? iface_.protocol_version : "";
    } else {
        have_iface_ = false;
    }
}

// Often called from inside the core (its send/poll_receive calls). melonDS DS polls in a loop and its stop() clears
// the functions that loop is calling, so stopping the core here would crash it. Only mark it; before_run() tells the
// core at the start of the next frame.
void Link::lost(const char *why) {
    if (sock_ == -1) return;
    LOGI("link lost: %s", why);
    close_socket();
    in_ = linkwire::Decoder();
    out_.clear();
    if (started_) stop_pending_ = true;
    set_state(Lost, why);
}

void Link::flush_out() {
    size_t done = 0;
    while (sock_ != -1 && done < out_.size()) {
        int n = (int)::send((sock_t)sock_, (const char *)out_.data() + done, (int)(out_.size() - done), SC_SEND_FLAGS);
        if (n > 0) {
            done += (size_t)n;
        } else {
            int e = sock_err();
            if (n < 0 && would_block(e)) break;
            lost("send failed");
            return;
        }
    }
    if (sock_ != -1 && done) out_.erase(out_.begin(), out_.begin() + (std::ptrdiff_t)done);
}

void Link::net_send(int flags, const void *buf, size_t len, uint16_t client_id) {
    (void)flags; // TCP: everything is reliable and ordered, like RetroArch
    if (sock_ == -1) return;
    if (buf && len > 0 && len <= linkwire::kMaxPacket && client_id != self_id_ &&
        (client_id == RETRO_NETPACKET_BROADCAST || client_id == peer_id_)) {
        if (out_.size() + 4 + len > 8u * 1024 * 1024) {
            lost("the other side stopped reading");
            return;
        }
        linkwire::encode(buf, len, out_);
        tx_++;
        tx_bytes_ += len;
    }
    flush_out(); // NULL/0 is a flush-only call; we always send straight away
}

// Reads everything waiting and hands complete packets to the core. Safe to call re-entrantly.
void Link::net_poll_receive() {
    if (sock_ == -1 || in_poll_) return;
    in_poll_ = true;
    uint8_t buf[16384];
    for (;;) {
        int n = (int)recv((sock_t)sock_, (char *)buf, (int)sizeof buf, 0);
        if (n == 0) {
            lost("the other side left");
            break;
        }
        if (n < 0) {
            if (!would_block(sock_err())) lost("receive failed");
            break;
        }
        in_.push(buf, (size_t)n);
        for (;;) {
            auto r = in_.next(packet_);
            if (r == linkwire::Decoder::Result::NeedMore) break;
            if (r == linkwire::Decoder::Result::Bad) {
                lost("bad packet");
                break;
            }
            rx_++;
            rx_bytes_ += packet_.size();
            if (started_ && iface_.receive) iface_.receive(packet_.data(), packet_.size(), peer_id_);
            if (sock_ == -1) break;
        }
        if (sock_ == -1) break;
    }
    in_poll_ = false;
}

void Link::before_run() {
    if (!configured_) return;
    // A link lost during the last frame: tell the core now, outside its callbacks.
    if (stop_pending_) {
        stop_pending_ = false;
        if (host_ && iface_.disconnected) iface_.disconnected(peer_id_);
        if (iface_.stop) iface_.stop();
        started_ = false;
    }
    // Start the session once the game is loaded (RetroArch also starts after load).
    if (!started_ && sock_ != -1) {
        if (!have_iface_) {
            LOGE("link: the core did not offer link support");
            set_state(NoSupport, "this emulator can't link");
            close_socket();
            return;
        }
        started_ = true;
        iface_.start(self_id_, send_cb, poll_receive_cb);
        if (host_ && iface_.connected && !iface_.connected(peer_id_)) {
            LOGE("link: the core refused the other side");
            if (iface_.stop) iface_.stop();
            started_ = false;
            close_socket();
            set_state(Refused, "the game refused the link");
            return;
        }
        set_state(Active);
        LOGI("link started as client %u (protocol %s)", self_id_, proto_.empty() ? "none" : proto_.c_str());
    }
    if (started_) {
        net_poll_receive();
        if (started_ && iface_.poll) iface_.poll();
    }
}

void Link::after_run() {
    if (sock_ != -1) flush_out();
}

void Link::shutdown() {
    if (started_ && iface_.stop) iface_.stop();
    started_ = stop_pending_ = false;
    if (sock_ != -1) {
        flush_out();
        close_socket();
    }
    if (state_ == Active || state_ == WaitingCore) set_state(Off, "game closed");
}
} // namespace sc
