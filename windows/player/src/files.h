/*
 * ShoalcadePlayer: file helpers, UTF-8 paths on every platform (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace sc {
/** fopen with a UTF-8 path (on Windows through _wfopen, so non-ASCII folder names work). */
FILE *open_file(const std::string &path, const char *mode);
bool read_file(const std::string &path, std::vector<uint8_t> &out, std::string *err = nullptr);
bool file_exists(const std::string &path);
/** Last change, seconds since 1970 (0 = not there). */
int64_t file_mtime(const std::string &path);
bool is_dir(const std::string &path);
/** Creates the folder and its parents. True if it exists afterwards. */
bool make_dirs(const std::string &path);
std::string parent_dir(const std::string &path);
std::string join_path(const std::string &dir, const std::string &name);
/**
 * Saves are precious: writes `<path>.tmp`, flushes it to the disk (fsync / FlushFileBuffers), then renames it over
 * `path` in one step, so a crash or power cut leaves either the old file or the new one, never half of one.
 */
bool write_file_atomic(const std::string &path, const void *data, size_t size, std::string *err = nullptr);
/**
 * Pictures only (screenshots, snapshot thumbnails), never saves or snapshots: removes any old `path`, then writes the
 * bytes straight into a new file of that name and flushes it. No temp file and no rename: some antivirus behaviour
 * monitors (Trend Micro, 01/11/2026) kill a process on its 3rd `X.png.tmp` -> `X.png` rename, as ransomware does that.
 * A crash mid-write can leave no picture or a cut-off one; readers treat a picture that won't decode as none (D225).
 */
bool write_file_direct(const std::string &path, const void *data, size_t size, std::string *err = nullptr);
} // namespace sc
