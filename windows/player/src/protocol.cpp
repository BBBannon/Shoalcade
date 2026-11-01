/*
 * ShoalcadePlayer: the control protocol (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "protocol.h"

#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <iostream>
#include <thread>

#include "log.h"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <fcntl.h>
#include <io.h>
#include <windows.h>
#else
#include <unistd.h>
#endif

namespace sc {
namespace {
std::mutex g_out_mutex;
int g_out_fd = 1;
std::condition_variable g_cv;

void write_all(const std::string &line) {
    const char *p = line.data();
    size_t left = line.size();
    while (left > 0) {
#ifdef _WIN32
        int w = _write(g_out_fd, p, (unsigned)left);
#else
        ssize_t w = write(g_out_fd, p, left);
#endif
        if (w <= 0) return; // the shell is gone; nothing more can be said
        p += w;
        left -= (size_t)w;
    }
}
} // namespace

void protocol_init() {
#ifdef _WIN32
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
    fflush(stdout);
    g_out_fd = _dup(1);
    _dup2(2, 1);
    SetStdHandle(STD_OUTPUT_HANDLE, GetStdHandle(STD_ERROR_HANDLE));
#else
    fflush(stdout);
    g_out_fd = dup(1);
    dup2(2, 1);
#endif
    if (g_out_fd < 0) g_out_fd = 2; // shouldn't happen; better to be heard on stderr than not at all
}

void send(const std::string &type, json fields) {
    if (!fields.is_object()) fields = json::object();
    json msg = json::object();
    msg["type"] = type;
    for (auto it = fields.begin(); it != fields.end(); ++it) msg[it.key()] = it.value();
    // Paths may hold any bytes: never let a bad UTF-8 sequence throw here.
    std::string line = msg.dump(-1, ' ', false, json::error_handler_t::replace) + "\n";
    std::lock_guard<std::mutex> lock(g_out_mutex);
    write_all(line);
}

void Inbox::start_stdin_reader() {
    std::thread([this] {
        std::string line;
        while (std::getline(std::cin, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.find_first_not_of(" \t") == std::string::npos) continue;
            json msg = json::parse(line, nullptr, false);
            if (msg.is_discarded() || !msg.is_object() || !msg.contains("type") || !msg["type"].is_string()) {
                send("error", {{"message", "Couldn't read a message from the shell (not a JSON object with a type)."},
                               {"fatal", false}});
                continue;
            }
            push(std::move(msg));
        }
        std::lock_guard<std::mutex> lock(mutex_);
        eof_ = true;
        g_cv.notify_all();
    }).detach();
}

void Inbox::push(json msg) {
    std::lock_guard<std::mutex> lock(mutex_);
    queue_.push_back(std::move(msg));
    g_cv.notify_all();
}

std::optional<json> Inbox::pop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (queue_.empty()) return std::nullopt;
    json m = std::move(queue_.front());
    queue_.pop_front();
    return m;
}

std::optional<json> Inbox::wait(int timeout_ms) {
    std::unique_lock<std::mutex> lock(mutex_);
    g_cv.wait_for(lock, std::chrono::milliseconds(timeout_ms), [this] { return !queue_.empty() || eof_; });
    if (queue_.empty()) return std::nullopt;
    json m = std::move(queue_.front());
    queue_.pop_front();
    return m;
}

bool Inbox::eof() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return eof_;
}
} // namespace sc
