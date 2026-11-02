/*
 * Shoalcade.exe: the Windows launcher of Shoalcade for Windows. Part of Shoalcade, GPL-3.0-or-later.
 * Built by tools/build_windows_release.sh with MinGW-w64 (docs/windows/phase8-installer.md).
 *
 * What it does:
 *  1. Velopack hooks (Velopack runs the main exe with --veloapp-install/-updated/-obsolete/-uninstall <version>):
 *     answers at once. On uninstall it tells the user that their games and saves are NOT deleted and where they are.
 *     It never deletes anything.
 *  2. Copies the cores' support files from the install folder (app\system\PPSSPP and app\system\dolphin-emu) to the
 *     user's data folder (%LOCALAPPDATA%\Shoalcade[ Dev]\system\...), which is the system folder the app gives the
 *     player, whenever the installed copy's stamp differs from the user's. Only those folders are written, and never
 *     their bios\ or memcards\ folders, an existing memory card (*.ps2) or IPL.bin (the user's own files).
 *  3. Starts the Java app IN THIS PROCESS (jvm.dll from runtime\, JNI invocation API, as jpackage's launcher does), so
 *     the running program is Shoalcade.exe: the taskbar and Task Manager show Shoalcade, and Velopack can stop it.
 *     Class path, main class and JVM options come from app\launcher.cfg, written at build time from the Gradle build.
 *
 * Install folder layout: Shoalcade.exe, runtime\ (Java), licences\, SOURCE.txt, and app\ = the jars, Skiko's DLL,
 * launcher.cfg, player\, cores\, system\PPSSPP\ and system\dolphin-emu\Sys\. player\ and cores\ sit beside the Compose resources folder
 * (app\resources), which is where the app's PlayerSetup.find looks for them (desktop/Cores.kt).
 */
#define WIN32_LEAN_AND_MEAN
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <shellapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <jni.h>

#ifndef SC_DATA_FOLDER
#error "SC_DATA_FOLDER must be defined (L\"Shoalcade\" or L\"Shoalcade Dev\")"
#endif
#ifndef SC_APP_NAME
#error "SC_APP_NAME must be defined"
#endif

#define MAX_OPTS 64
#define MAX_CP 256

static wchar_t g_root[MAX_PATH];      /* install folder (where this exe is), no trailing slash */
static wchar_t g_root_jvm[MAX_PATH];  /* the same, short (8.3) form when it has non-ASCII characters */

static void fail(const wchar_t *what, const wchar_t *detail) {
    wchar_t msg[2048];
    _snwprintf(msg, 2047, L"%ls could not start.\n\n%ls\n%ls\n\nReinstalling %ls should fix this. Your games and saves are not affected.",
               SC_APP_NAME, what, detail ? detail : L"", SC_APP_NAME);
    msg[2047] = 0;
    MessageBoxW(NULL, msg, SC_APP_NAME, MB_OK | MB_ICONERROR);
}

/* The user's data folder, exactly as the app's Paths.dataDirFor: %LOCALAPPDATA% (else <home>\AppData\Local) + name. */
static BOOL data_folder(wchar_t *out, size_t n) {
    wchar_t local[MAX_PATH];
    DWORD k = GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH);
    if (k == 0 || k >= MAX_PATH) {
        wchar_t home[MAX_PATH];
        k = GetEnvironmentVariableW(L"USERPROFILE", home, MAX_PATH);
        if (k == 0 || k >= MAX_PATH) return FALSE;
        _snwprintf(local, MAX_PATH, L"%ls\\AppData\\Local", home);
        local[MAX_PATH - 1] = 0;
    }
    _snwprintf(out, n, L"%ls\\%ls", local, SC_DATA_FOLDER);
    out[n - 1] = 0;
    return TRUE;
}

/* ---------- Velopack hooks ---------- */

static int hook(const wchar_t *name) {
    if (wcscmp(name, L"--veloapp-uninstall") == 0) {
        wchar_t data[MAX_PATH], msg[2048];
        if (!data_folder(data, MAX_PATH)) wcscpy(data, L"%LOCALAPPDATA%\\" SC_DATA_FOLDER);
        _snwprintf(msg, 2047,
                   L"%ls is being removed from this PC.\n\n"
                   L"Your games, saves, snapshots and settings are NOT deleted. They stay in:\n%ls\n\n"
                   L"Saves you haven't uploaded to your Jellyfin yet are only in that folder. "
                   L"To keep them, install %ls again and upload them, or copy that folder somewhere safe.\n\n"
                   L"If you want everything gone, delete that folder yourself once your saves are safe.",
                   SC_APP_NAME, data, SC_APP_NAME);
        msg[2047] = 0;
        /* Velopack waits up to 60 s for this hook, then carries on; the folder is kept either way. */
        MessageBoxW(NULL, msg, SC_APP_NAME, MB_OK | MB_ICONINFORMATION | MB_TOPMOST | MB_SETFOREGROUND);
    }
    /* install / updated / obsolete: nothing to do (PPSSPP and Dolphin files are copied when the app starts). */
    return 0;
}

/* ---------- Core support files (PPSSPP, Dolphin) ---------- */

static BOOL read_small_file(const wchar_t *path, char *buf, DWORD cap, DWORD *len) {
    HANDLE h = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (h == INVALID_HANDLE_VALUE) return FALSE;
    BOOL ok = ReadFile(h, buf, cap, len, NULL);
    CloseHandle(h);
    return ok;
}

/* The user's own firmware and memory cards live in the same system folders (pcsx2\bios\, pcsx2\memcards\; a GameCube
 * IPL.bin). The build never packs them (build_windows_release.sh refuses to), and this copy never writes there either:
 * folders named bios or memcards are skipped at any depth, and a memory card or IPL.bin that is already there is kept. */
static BOOL is_user_folder(const wchar_t *name) {
    return _wcsicmp(name, L"bios") == 0 || _wcsicmp(name, L"memcards") == 0;
}

static BOOL is_user_file(const wchar_t *name) {
    size_t n = wcslen(name);
    return _wcsicmp(name, L"IPL.bin") == 0 || (n >= 4 && _wcsicmp(name + n - 4, L".ps2") == 0);
}

static BOOL copy_tree(const wchar_t *src, const wchar_t *dst) {
    wchar_t pattern[MAX_PATH], s[MAX_PATH], d[MAX_PATH];
    WIN32_FIND_DATAW fd;
    BOOL ok = TRUE;
    CreateDirectoryW(dst, NULL);
    _snwprintf(pattern, MAX_PATH, L"%ls\\*", src);
    pattern[MAX_PATH - 1] = 0;
    HANDLE h = FindFirstFileW(pattern, &fd);
    if (h == INVALID_HANDLE_VALUE) return FALSE;
    do {
        if (wcscmp(fd.cFileName, L".") == 0 || wcscmp(fd.cFileName, L"..") == 0) continue;
        _snwprintf(s, MAX_PATH, L"%ls\\%ls", src, fd.cFileName);
        _snwprintf(d, MAX_PATH, L"%ls\\%ls", dst, fd.cFileName);
        s[MAX_PATH - 1] = d[MAX_PATH - 1] = 0;
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            if (!is_user_folder(fd.cFileName)) ok = copy_tree(s, d) && ok;
        } else if (is_user_file(fd.cFileName)) {
            /* never overwrite: fails with ERROR_FILE_EXISTS when the user has one, which is fine */
            if (!CopyFileW(s, d, TRUE) && GetLastError() != ERROR_FILE_EXISTS) ok = FALSE;
        } else ok = CopyFileW(s, d, FALSE) && ok;
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return ok;
}

/* app\system\<name>.stamp (written at build time: a hash of the folder) decides whether the user's copy is current.
 * Files are only added or overwritten, never deleted (the core may keep its own files there).
 * Folders: PPSSPP (PSP), dolphin-emu (Dolphin's Sys folder, GameCube + Wii) and pcsx2 (PCEE2's resources, PS2). */
static void sync_system_folder(const wchar_t *name) {
    wchar_t src[MAX_PATH], stamp_src[MAX_PATH], data[MAX_PATH], sys[MAX_PATH], dst[MAX_PATH], stamp_dst[MAX_PATH];
    char a[256], b[256];
    DWORD la = 0, lb = 0;
    _snwprintf(src, MAX_PATH, L"%ls\\app\\system\\%ls", g_root, name);
    _snwprintf(stamp_src, MAX_PATH, L"%ls\\app\\system\\%ls.stamp", g_root, name);
    if (!read_small_file(stamp_src, a, sizeof a, &la) || la == 0) return; /* nothing packed */
    if (!data_folder(data, MAX_PATH)) return;
    _snwprintf(sys, MAX_PATH, L"%ls\\system", data);
    _snwprintf(dst, MAX_PATH, L"%ls\\%ls", sys, name);
    _snwprintf(stamp_dst, MAX_PATH, L"%ls\\%ls.stamp", sys, name);
    if (read_small_file(stamp_dst, b, sizeof b, &lb) && la == lb && memcmp(a, b, la) == 0) return;
    CreateDirectoryW(data, NULL);
    CreateDirectoryW(sys, NULL);
    if (!copy_tree(src, dst)) return; /* stamp not written: tried again next start */
    HANDLE h = CreateFileW(stamp_dst, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h != INVALID_HANDLE_VALUE) {
        DWORD w;
        WriteFile(h, a, la, &w, NULL);
        CloseHandle(h);
    }
}

/* ---------- launcher.cfg ---------- */

static char *g_cfg;                 /* file contents (UTF-8), kept for the life of the process */
static char *g_main;                /* main=  (dots) */
static char *g_cp[MAX_CP];          /* cp=    (relative to app\) */
static int g_ncp;
static char *g_opt[MAX_OPTS];       /* opt=   ($APPDIR = install folder\app, $ROOT = install folder) */
static int g_nopt;

static BOOL load_cfg(void) {
    wchar_t path[MAX_PATH];
    char *p;
    _snwprintf(path, MAX_PATH, L"%ls\\app\\launcher.cfg", g_root);
    HANDLE h = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (h == INVALID_HANDLE_VALUE) return FALSE;
    DWORD size = GetFileSize(h, NULL), got = 0;
    g_cfg = (char *)calloc(size + 1, 1);
    BOOL ok = g_cfg && ReadFile(h, g_cfg, size, &got, NULL) && got == size;
    CloseHandle(h);
    if (!ok) return FALSE;
    for (p = strtok(g_cfg, "\r\n"); p; p = strtok(NULL, "\r\n")) {
        if (strncmp(p, "main=", 5) == 0) g_main = p + 5;
        else if (strncmp(p, "cp=", 3) == 0 && g_ncp < MAX_CP) g_cp[g_ncp++] = p + 3;
        else if (strncmp(p, "opt=", 4) == 0 && g_nopt < MAX_OPTS) g_opt[g_nopt++] = p + 4;
    }
    return g_main && g_ncp > 0;
}

/* The JVM reads option strings in the ANSI code page. The install folder is under the user's profile, so it can
 * hold any character: use its 8.3 short form when it isn't plain ASCII. */
static void make_jvm_root(void) {
    BOOL ascii = TRUE;
    for (const wchar_t *c = g_root; *c; c++) if (*c > 127) ascii = FALSE;
    wcscpy(g_root_jvm, g_root);
    if (!ascii) {
        wchar_t s[MAX_PATH];
        DWORD k = GetShortPathNameW(g_root, s, MAX_PATH);
        if (k > 0 && k < MAX_PATH) wcscpy(g_root_jvm, s);
    }
}

static char *acp(const wchar_t *w) {
    int n = WideCharToMultiByte(CP_ACP, 0, w, -1, NULL, 0, NULL, NULL);
    char *s = (char *)malloc(n > 0 ? n : 1);
    if (n > 0) WideCharToMultiByte(CP_ACP, 0, w, -1, s, n, NULL, NULL); else s[0] = 0;
    return s;
}

/* Replace $ROOT and $APPDIR in a (UTF-8, ASCII in practice) cfg string; result in the ANSI code page. */
static char *expand(const char *in, const char *root) {
    size_t cap = strlen(in) + 8 * strlen(root) + 16;
    char *out = (char *)malloc(cap), *o = out;
    while (*in) {
        if (strncmp(in, "$APPDIR", 7) == 0) { o += sprintf(o, "%s\\app", root); in += 7; }
        else if (strncmp(in, "$ROOT", 5) == 0) { o += sprintf(o, "%s", root); in += 5; }
        else *o++ = *in++;
    }
    *o = 0;
    return out;
}

/* ---------- the JVM ---------- */

typedef jint(JNICALL *CreateJavaVM_t)(JavaVM **, void **, void *);

static int g_argc;
static wchar_t **g_argv;
static int g_exit = 0;

static DWORD WINAPI java_main(LPVOID unused) {
    (void)unused;
    wchar_t jvm_path[MAX_PATH], bin[MAX_PATH];
    _snwprintf(bin, MAX_PATH, L"%ls\\runtime\\bin", g_root);
    _snwprintf(jvm_path, MAX_PATH, L"%ls\\runtime\\bin\\server\\jvm.dll", g_root);
    SetDllDirectoryW(bin); /* jvm.dll's own dependencies (vcruntime, ucrt) live in runtime\bin */
    HMODULE jvm = LoadLibraryW(jvm_path);
    if (!jvm) { fail(L"The Java runtime is missing:", jvm_path); g_exit = 2; return 0; }
    CreateJavaVM_t create = (CreateJavaVM_t)(void *)GetProcAddress(jvm, "JNI_CreateJavaVM");
    if (!create) { fail(L"The Java runtime is damaged:", jvm_path); g_exit = 2; return 0; }

    char *root = acp(g_root_jvm);
    JavaVMOption opts[MAX_OPTS + 4];
    int n = 0;
    /* class path */
    size_t cplen = 32;
    for (int i = 0; i < g_ncp; i++) cplen += strlen(root) + strlen(g_cp[i]) + 8;
    char *cp = (char *)malloc(cplen), *c = cp;
    c += sprintf(c, "-Djava.class.path=");
    for (int i = 0; i < g_ncp; i++) c += sprintf(c, "%s%s\\app\\%s", i ? ";" : "", root, g_cp[i]);
    opts[n++].optionString = cp;
    for (int i = 0; i < g_nopt; i++) opts[n++].optionString = expand(g_opt[i], root);
    for (int i = 0; i < n; i++) opts[i].extraInfo = NULL;

    JavaVMInitArgs args;
    args.version = JNI_VERSION_10;
    args.nOptions = n;
    args.options = opts;
    args.ignoreUnrecognized = JNI_FALSE;
    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    if (create(&vm, (void **)&env, &args) != JNI_OK) {
        fail(L"Java could not start (JNI_CreateJavaVM failed).", NULL);
        g_exit = 3;
        return 0;
    }
    char cls[256];
    strncpy(cls, g_main, sizeof cls - 1);
    cls[sizeof cls - 1] = 0;
    for (char *q = cls; *q; q++) if (*q == '.') *q = '/';
    jclass main_cls = (*env)->FindClass(env, cls);
    jmethodID main_id = main_cls ? (*env)->GetStaticMethodID(env, main_cls, "main", "([Ljava/lang/String;)V") : NULL;
    if (!main_id) {
        (*env)->ExceptionDescribe(env);
        fail(L"The app's main class was not found.", NULL);
        g_exit = 4;
    } else {
        jclass str = (*env)->FindClass(env, "java/lang/String");
        int extra = g_argc > 1 ? g_argc - 1 : 0;
        jobjectArray jargs = (*env)->NewObjectArray(env, extra, str, NULL);
        for (int i = 0; i < extra; i++) {
            const wchar_t *a = g_argv[i + 1];
            jstring s = (*env)->NewString(env, (const jchar *)a, (jsize)wcslen(a));
            (*env)->SetObjectArrayElement(env, jargs, i, s);
        }
        (*env)->CallStaticVoidMethod(env, main_cls, main_id, jargs);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            g_exit = 1;
        }
    }
    /* Waits for the app's own threads (the window) to finish, like java.exe. System.exit ends the process directly. */
    (*vm)->DestroyJavaVM(vm);
    return 0;
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmd, int show) {
    (void)inst; (void)prev; (void)cmd; (void)show;
    g_argv = CommandLineToArgvW(GetCommandLineW(), &g_argc);
    if (g_argv && g_argc >= 2 && wcsncmp(g_argv[1], L"--veloapp-", 10) == 0) return hook(g_argv[1]);

    DWORD k = GetModuleFileNameW(NULL, g_root, MAX_PATH);
    if (k == 0 || k >= MAX_PATH) { fail(L"Its folder path is too long.", NULL); return 2; }
    wchar_t *slash = wcsrchr(g_root, L'\\');
    if (slash) *slash = 0;
    make_jvm_root();

    if (!load_cfg()) {
        wchar_t p[MAX_PATH];
        _snwprintf(p, MAX_PATH, L"%ls\\app\\launcher.cfg", g_root);
        fail(L"A file is missing:", p);
        return 2;
    }
    sync_system_folder(L"PPSSPP");
    sync_system_folder(L"dolphin-emu");
    sync_system_folder(L"pcsx2"); /* PCEE2's resources; the user's bios\ and memcards\ there are never touched */

    /* Java's launcher also runs main on a new thread, to choose its stack size. */
    HANDLE t = CreateThread(NULL, 8u << 20, java_main, NULL, STACK_SIZE_PARAM_IS_A_RESERVATION, NULL);
    if (!t) { fail(L"Could not start a thread.", NULL); return 2; }
    WaitForSingleObject(t, INFINITE);
    CloseHandle(t);
    return g_exit;
}
