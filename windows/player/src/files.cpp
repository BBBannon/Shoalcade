/*
 * ShoalcadePlayer: file helpers (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "files.h"

#include <cerrno>
#include <cstring>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <io.h>
#include <sys/stat.h>
#include <sys/types.h>
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace sc {
#ifdef _WIN32
static std::wstring widen(const std::string &s) {
    if (s.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), w.data(), n);
    return w;
}
static std::string last_error_text() {
    DWORD e = GetLastError();
    char buf[256] = {0};
    FormatMessageA(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, nullptr, e, 0, buf, sizeof buf, nullptr);
    std::string s(buf);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    return s + " (" + std::to_string(e) + ")";
}
#endif

FILE *open_file(const std::string &path, const char *mode) {
#ifdef _WIN32
    return _wfopen(widen(path).c_str(), widen(mode).c_str());
#else
    return fopen(path.c_str(), mode);
#endif
}

bool read_file(const std::string &path, std::vector<uint8_t> &out, std::string *err) {
    FILE *f = open_file(path, "rb");
    if (!f) {
        if (err) *err = std::string("can't open: ") + strerror(errno);
        return false;
    }
    out.clear();
    uint8_t buf[65536];
    size_t n;
    while ((n = fread(buf, 1, sizeof buf, f)) > 0) out.insert(out.end(), buf, buf + n);
    bool ok = !ferror(f);
    fclose(f);
    if (!ok && err) *err = "read error";
    return ok;
}

bool file_exists(const std::string &path) {
#ifdef _WIN32
    DWORD a = GetFileAttributesW(widen(path).c_str());
    return a != INVALID_FILE_ATTRIBUTES && !(a & FILE_ATTRIBUTE_DIRECTORY);
#else
    struct stat st;
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
#endif
}

int64_t file_mtime(const std::string &path) {
#ifdef _WIN32
    struct _stat64 st;
    return _wstat64(widen(path).c_str(), &st) == 0 ? (int64_t)st.st_mtime : 0;
#else
    struct stat st;
    return stat(path.c_str(), &st) == 0 ? (int64_t)st.st_mtime : 0;
#endif
}

bool is_dir(const std::string &path) {
#ifdef _WIN32
    DWORD a = GetFileAttributesW(widen(path).c_str());
    return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY);
#else
    struct stat st;
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
#endif
}

static bool is_sep(char c) {
#ifdef _WIN32
    return c == '/' || c == '\\';
#else
    return c == '/';
#endif
}

std::string parent_dir(const std::string &path) {
    size_t i = path.size();
    while (i > 0 && !is_sep(path[i - 1])) i--;
    if (i == 0) return ".";
    while (i > 1 && is_sep(path[i - 1])) i--;
    return path.substr(0, i);
}

std::string join_path(const std::string &dir, const std::string &name) {
    if (dir.empty()) return name;
    if (is_sep(dir.back())) return dir + name;
#ifdef _WIN32
    return dir + "\\" + name;
#else
    return dir + "/" + name;
#endif
}

bool make_dirs(const std::string &path) {
    if (path.empty() || is_dir(path)) return true;
    std::string parent = parent_dir(path);
    if (parent != path && parent != "." && !make_dirs(parent)) return false;
#ifdef _WIN32
    CreateDirectoryW(widen(path).c_str(), nullptr);
#else
    mkdir(path.c_str(), 0755);
#endif
    return is_dir(path);
}

bool write_file_atomic(const std::string &path, const void *data, size_t size, std::string *err) {
    std::string tmp = path + ".tmp";
#ifdef _WIN32
    std::wstring wtmp = widen(tmp), wpath = widen(path);
    HANDLE h = CreateFileW(wtmp.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        if (err) *err = "can't create " + tmp + ": " + last_error_text();
        return false;
    }
    const uint8_t *p = static_cast<const uint8_t *>(data);
    size_t left = size;
    bool ok = true;
    while (left > 0 && ok) {
        DWORD chunk = left > (1u << 30) ? (1u << 30) : (DWORD)left, wrote = 0;
        ok = WriteFile(h, p, chunk, &wrote, nullptr) && wrote == chunk;
        p += wrote;
        left -= wrote;
    }
    if (ok) ok = FlushFileBuffers(h);
    std::string why = ok ? "" : last_error_text();
    CloseHandle(h);
    if (ok && !MoveFileExW(wtmp.c_str(), wpath.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        ok = false;
        why = "rename failed: " + last_error_text();
    }
    if (!ok) {
        DeleteFileW(wtmp.c_str());
        if (err) *err = why;
    }
    return ok;
#else
    int fd = open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) {
        if (err) *err = "can't create " + tmp + ": " + strerror(errno);
        return false;
    }
    const uint8_t *p = static_cast<const uint8_t *>(data);
    size_t left = size;
    while (left > 0) {
        ssize_t w = write(fd, p, left);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) {
            if (err) *err = std::string("write failed: ") + strerror(errno);
            close(fd);
            unlink(tmp.c_str());
            return false;
        }
        p += w;
        left -= (size_t)w;
    }
    if (fsync(fd) != 0) {
        if (err) *err = std::string("fsync failed: ") + strerror(errno);
        close(fd);
        unlink(tmp.c_str());
        return false;
    }
    close(fd);
    if (rename(tmp.c_str(), path.c_str()) != 0) {
        if (err) *err = std::string("rename failed: ") + strerror(errno);
        unlink(tmp.c_str());
        return false;
    }
    // The rename itself is only durable once the folder is flushed too.
    int dfd = open(parent_dir(path).c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dfd >= 0) {
        fsync(dfd);
        close(dfd);
    }
    return true;
#endif
}

bool write_file_direct(const std::string &path, const void *data, size_t size, std::string *err) {
    // See files.h (D225): delete + create a new file + flush; a failed write never leaves a part-file behind.
#ifdef _WIN32
    std::wstring wpath = widen(path);
    if (!DeleteFileW(wpath.c_str()) && GetLastError() != ERROR_FILE_NOT_FOUND) {
        if (err) *err = "can't replace " + path + ": " + last_error_text();
        return false;
    }
    HANDLE h = CreateFileW(wpath.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        if (err) *err = "can't create " + path + ": " + last_error_text();
        return false;
    }
    const uint8_t *p = static_cast<const uint8_t *>(data);
    size_t left = size;
    bool ok = true;
    while (left > 0 && ok) {
        DWORD chunk = left > (1u << 30) ? (1u << 30) : (DWORD)left, wrote = 0;
        ok = WriteFile(h, p, chunk, &wrote, nullptr) && wrote == chunk;
        p += wrote;
        left -= wrote;
    }
    if (ok) ok = FlushFileBuffers(h);
    std::string why = ok ? "" : last_error_text();
    CloseHandle(h);
    if (!ok) {
        DeleteFileW(wpath.c_str());
        if (err) *err = why;
    }
    return ok;
#else
    if (unlink(path.c_str()) != 0 && errno != ENOENT) {
        if (err) *err = "can't replace " + path + ": " + strerror(errno);
        return false;
    }
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0644);
    if (fd < 0) {
        if (err) *err = "can't create " + path + ": " + strerror(errno);
        return false;
    }
    const uint8_t *p = static_cast<const uint8_t *>(data);
    size_t left = size;
    while (left > 0) {
        ssize_t w = write(fd, p, left);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) {
            if (err) *err = std::string("write failed: ") + strerror(errno);
            close(fd);
            unlink(path.c_str());
            return false;
        }
        p += w;
        left -= (size_t)w;
    }
    if (fsync(fd) != 0) {
        if (err) *err = std::string("fsync failed: ") + strerror(errno);
        close(fd);
        unlink(path.c_str());
        return false;
    }
    close(fd);
    return true;
#endif
}
} // namespace sc
