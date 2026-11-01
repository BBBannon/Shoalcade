/*
 * ShoalcadePlayer: logging (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Logs go to stderr and, if set, a log file. Never stdout: stdout is only for protocol messages.
 * The player never holds a Jellyfin token, so nothing secret can reach a log.
 */
#pragma once
#include <string>

namespace sc {
enum class LogLevel { Debug = 0, Info, Warn, Error };
void log_open_file(const std::string &path);
void log_set_level(LogLevel min);
#if defined(__MINGW32__)
#define SC_PRINTF(a, b) __attribute__((format(gnu_printf, a, b))) // with __USE_MINGW_ANSI_STDIO: C99 %zu etc.
#elif defined(__GNUC__)
#define SC_PRINTF(a, b) __attribute__((format(printf, a, b)))
#else
#define SC_PRINTF(a, b)
#endif
void logf(LogLevel level, const char *fmt, ...) SC_PRINTF(2, 3);
/** Logs `what` only the first time this key is seen (e.g. an unknown environment command). */
void log_once(const std::string &key, const char *fmt, ...) SC_PRINTF(2, 3);
} // namespace sc

#define LOGD(...) ::sc::logf(::sc::LogLevel::Debug, __VA_ARGS__)
#define LOGI(...) ::sc::logf(::sc::LogLevel::Info, __VA_ARGS__)
#define LOGW(...) ::sc::logf(::sc::LogLevel::Warn, __VA_ARGS__)
#define LOGE(...) ::sc::logf(::sc::LogLevel::Error, __VA_ARGS__)
