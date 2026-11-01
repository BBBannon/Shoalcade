/*
 * ShoalcadePlayer: logging (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "log.h"

#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <set>

#include "files.h"

namespace sc {
namespace {
std::mutex g_mutex;
FILE *g_file = nullptr;
LogLevel g_min = LogLevel::Info;
std::set<std::string> g_once;

void write_line(LogLevel level, const char *fmt, va_list ap) {
    static const char *names[] = {"D", "I", "W", "E"};
    char msg[2048];
    vsnprintf(msg, sizeof msg, fmt, ap);
    // Cores often end their lines with \n; we add our own.
    size_t n = std::char_traits<char>::length(msg);
    while (n > 0 && (msg[n - 1] == '\n' || msg[n - 1] == '\r')) msg[--n] = 0;
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                  std::chrono::steady_clock::now().time_since_epoch()).count();
    std::lock_guard<std::mutex> lock(g_mutex);
    fprintf(stderr, "[%lld.%03lld %s] %s\n", (long long)(ms / 1000), (long long)(ms % 1000), names[(int)level], msg);
    fflush(stderr);
    if (g_file) {
        fprintf(g_file, "[%lld.%03lld %s] %s\n", (long long)(ms / 1000), (long long)(ms % 1000), names[(int)level], msg);
        fflush(g_file);
    }
}
} // namespace

void log_open_file(const std::string &path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_file) fclose(g_file);
    g_file = open_file(path, "ab");
}

void log_set_level(LogLevel min) { g_min = min; }

void logf(LogLevel level, const char *fmt, ...) {
    if (level < g_min) return;
    va_list ap;
    va_start(ap, fmt);
    write_line(level, fmt, ap);
    va_end(ap);
}

void log_once(const std::string &key, const char *fmt, ...) {
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_once.insert(key).second) return;
    }
    va_list ap;
    va_start(ap, fmt);
    write_line(LogLevel::Info, fmt, ap);
    va_end(ap);
}
} // namespace sc
