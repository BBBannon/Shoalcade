/*
 * ShoalcadePlayer: the control protocol, one JSON object per line (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * stdout carries ONLY protocol messages. protocol_init() moves the real stdout to a private descriptor and points
 * descriptor 1 at stderr, so a core that printf()s can never corrupt the protocol. See PROTOCOL.md.
 */
#pragma once
#include <deque>
#include <mutex>
#include <optional>
#include <string>

#include "nlohmann/json.hpp"

namespace sc {
using json = nlohmann::json;

void protocol_init();
/** Sends {"type": type, ...fields} as one line. Thread-safe. */
void send(const std::string &type, json fields = json::object());

/** Messages from the shell, read on a background thread. */
class Inbox {
public:
    /** Starts the stdin reader. A line that isn't a JSON object is reported with an error message and dropped. */
    void start_stdin_reader();
    void push(json msg);
    std::optional<json> pop();
    /** Blocks until a message arrives or the timeout (ms) passes. */
    std::optional<json> wait(int timeout_ms);
    bool eof() const;

private:
    mutable std::mutex mutex_;
    std::deque<json> queue_;
    bool eof_ = false;
};
} // namespace sc
