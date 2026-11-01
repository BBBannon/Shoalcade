/*
 * ShoalcadePlayer: link play, the libretro netpacket interface carried over a local byte stream (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The Windows player's copy of the phone's link shim (android/app/src/main/cpp/linkshim.c). The split is the same
 * as on the phone: the shell does all the networking (discovery, PIN handshake, internet relay, Celio) and gives the
 * player a ready stream; the player answers RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE (78) and carries the core's
 * packets on that stream.
 *
 * Phone: Kotlin hands the shim a connected socket fd (SC_LINK_FD). Windows: the player connects to 127.0.0.1:<port>
 * where the shell is listening, and (if the shell gave a token) first writes "<token>\n" so the shell knows it is
 * really its player. After that line the stream carries exactly what linkshim.c's socket carries:
 *
 *   every packet, both directions: u32 big-endian payload length (1 .. 65536), then the payload.
 *
 * Rules, all as linkshim.c (and RetroArch):
 *  - host = client id 0 (link master), joiner = client id 1. Host: start(0) then connected(1); joiner: start(1).
 *  - the session starts on the first frame after the game is loaded.
 *  - each frame: read the stream -> receive() for each packet -> poll() -> retro_run -> flush what is queued.
 *  - every send is reliable and ordered (TCP); sends addressed to ourselves are dropped; NULL/0 is a flush.
 *  - a lost stream is only marked inside core callbacks; the core is told (disconnected + stop) next frame.
 */
#pragma once
#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "libretro/libretro.h"

namespace sc {
namespace linkwire {
constexpr size_t kMaxPacket = 64 * 1024; // linkshim.c MAX_PACKET

/** Appends one frame (u32 BE length + payload) to out. False (nothing added) for an empty or too-big packet. */
bool encode(const void *buf, size_t len, std::vector<uint8_t> &out);

/** Splits a byte stream into packets, exactly as linkshim.c's net_poll_receive does. */
class Decoder {
public:
    enum class Result { Packet, NeedMore, Bad };
    void push(const void *data, size_t len);
    /** Packet: `out` holds the next payload. Bad: length 0 or over kMaxPacket (the link is dropped). */
    Result next(std::vector<uint8_t> &out);
    size_t buffered() const { return buf_.size() - off_; }

private:
    std::vector<uint8_t> buf_;
    size_t off_ = 0;
};
} // namespace linkwire

class Link {
public:
    /** Same numbers as the phone's linkstate.h / LinkNative, so the shell can share the words. */
    enum State { Off = 0, WaitingCore = 1, Active = 2, Lost = 3, Refused = 4, NoSupport = 5 };
    static const char *state_name(int s);

    struct Config {
        int port = 0;           // 127.0.0.1:<port>, where the shell listens
        bool host = false;      // role "host" (client 0) or "join" (client 1)
        std::string token;      // written as "<token>\n" first (empty = no line)
        int connect_timeout_ms = 10000;
    };

    Link();
    ~Link();

    /** Connects to the shell's stream. False with `err` if it can't. */
    bool open(const Config &c, std::string &err);
    bool configured() const { return configured_; }
    bool host() const { return host_; }

    /** RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE. Always accepted (the interface is copied). */
    void set_interface(const retro_netpacket_callback *cb);
    /** Before each retro_run: tell the core about a lost link, start the session, read, poll. */
    void before_run();
    /** After each retro_run: push what the core queued. */
    void after_run();
    /** Before retro_unload_game / retro_deinit: stop the core's session and close the stream. */
    void shutdown();

    int state() const { return state_; }
    uint64_t received() const { return rx_; }
    uint64_t sent() const { return tx_; }
    uint64_t bytes_received() const { return rx_bytes_; }
    uint64_t bytes_sent() const { return tx_bytes_; }
    const std::string &protocol_version() const { return proto_; }
    const std::string &why() const { return why_; }

    /** Called whenever the state changes (the player sends `linkStatus`). */
    std::function<void()> on_change;

    // The core's callbacks (static trampolines in link.cpp forward here).
    void net_send(int flags, const void *buf, size_t len, uint16_t client_id);
    void net_poll_receive();

private:
    void set_state(int s, const std::string &why = "");
    void lost(const char *why);
    void flush_out();
    void close_socket();

    intptr_t sock_ = -1;
    bool configured_ = false, host_ = false;
    uint16_t self_id_ = 0, peer_id_ = 1;
    bool have_iface_ = false, started_ = false, in_poll_ = false, stop_pending_ = false;
    retro_netpacket_callback iface_{};
    std::string proto_;
    linkwire::Decoder in_;
    std::vector<uint8_t> out_;
    std::vector<uint8_t> packet_;
    int state_ = Off;
    std::string why_;
    uint64_t rx_ = 0, tx_ = 0, rx_bytes_ = 0, tx_bytes_ = 0;
};
} // namespace sc
