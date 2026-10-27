#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <dirent.h>

static const char* kPattern1 = "/data/data/com.termux/files";
static const char* kPattern2 = "/data/user/0/com.termux/files";
static const char* kPattern3 = "/data/data/com.termux";
static const char* kPattern4 = "/data/user/0/com.termux";

static char g_real_files[512] = {0};
static char g_real_app[512] = {0};
static int g_initialized = 0;

static void init_redirect_paths(void) {
    if (g_initialized) return;

    const char* env_files = getenv("BOOMAXE_FILES");
    if (env_files && env_files[0]) {
        strncpy(g_real_files, env_files, sizeof(g_real_files) - 1);
    } else {
        const char* prefix = getenv("PREFIX");
        if (prefix && prefix[0]) {
            // PREFIX is usually .../files/usr
            char temp[512];
            strncpy(temp, prefix, sizeof(temp) - 1);
            char* usr_part = strstr(temp, "/usr");
            if (usr_part) *usr_part = '\0';
            strncpy(g_real_files, temp, sizeof(g_real_files) - 1);
        }
    }

    if (g_real_files[0]) {
        strncpy(g_real_app, g_real_files, sizeof(g_real_app) - 1);
        char* files_part = strstr(g_real_app, "/files");
        if (files_part) *files_part = '\0';
    }

    g_initialized = 1;
}

static const char* redirect_path(const char* path, char* buffer, size_t bufsize) {
    if (!path) return path;
    init_redirect_paths();
    if (!g_real_files[0]) return path;

    if (strncmp(path, kPattern1, strlen(kPattern1)) == 0) {
        snprintf(buffer, bufsize, "%s%s", g_real_files, path + strlen(kPattern1));
        return buffer;
    }
    if (strncmp(path, kPattern2, strlen(kPattern2)) == 0) {
        snprintf(buffer, bufsize, "%s%s", g_real_files, path + strlen(kPattern2));
        return buffer;
    }
    if (strncmp(path, kPattern3, strlen(kPattern3)) == 0) {
        snprintf(buffer, bufsize, "%s%s", g_real_app, path + strlen(kPattern3));
        return buffer;
    }
    if (strncmp(path, kPattern4, strlen(kPattern4)) == 0) {
        snprintf(buffer, bufsize, "%s%s", g_real_app, path + strlen(kPattern4));
        return buffer;
    }

    return path;
}

// ---------------------------------------------------------------- open / openat

typedef int (*open_func)(const char*, int, ...);
int open(const char* pathname, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & 020000000)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t) va_arg(ap, int);
        va_end(ap);
    }
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static open_func real_fn = NULL;
    if (!real_fn) real_fn = (open_func) dlsym(RTLD_NEXT, "open");
    return real_fn ? real_fn(rpath, flags, mode) : -1;
}

FILE* fopen(const char* pathname, const char* mode) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static FILE* (*real_fn)(const char*, const char*) = NULL;
    if (!real_fn) real_fn = (FILE* (*)(const char*, const char*)) dlsym(RTLD_NEXT, "fopen");
    return real_fn ? real_fn(rpath, mode) : NULL;
}

FILE* fopen64(const char* pathname, const char* mode) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static FILE* (*real_fn)(const char*, const char*) = NULL;
    if (!real_fn) real_fn = (FILE* (*)(const char*, const char*)) dlsym(RTLD_NEXT, "fopen64");
    if (!real_fn) real_fn = (FILE* (*)(const char*, const char*)) dlsym(RTLD_NEXT, "fopen");
    return real_fn ? real_fn(rpath, mode) : NULL;
}

char* realpath(const char* path, char* resolved_path) {
    char buf[1024];
    const char* rpath = redirect_path(path, buf, sizeof(buf));
    static char* (*real_fn)(const char*, char*) = NULL;
    if (!real_fn) real_fn = (char* (*)(const char*, char*)) dlsym(RTLD_NEXT, "realpath");
    return real_fn ? real_fn(rpath, resolved_path) : NULL;
}

typedef int (*openat_func)(int, const char*, int, ...);
int openat(int dirfd, const char* pathname, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & 020000000)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t) va_arg(ap, int);
        va_end(ap);
    }
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static openat_func real_fn = NULL;
    if (!real_fn) real_fn = (openat_func) dlsym(RTLD_NEXT, "openat");
    return real_fn ? real_fn(dirfd, rpath, flags, mode) : -1;
}

// ---------------------------------------------------------------- stat / access

int stat(const char* pathname, struct stat* statbuf) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*, struct stat*) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "stat");
    return real_fn ? real_fn(rpath, statbuf) : -1;
}

int lstat(const char* pathname, struct stat* statbuf) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*, struct stat*) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "lstat");
    return real_fn ? real_fn(rpath, statbuf) : -1;
}

int fstatat(int dirfd, const char* pathname, struct stat* statbuf, int flags) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(int, const char*, struct stat*, int) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "fstatat");
    return real_fn ? real_fn(dirfd, rpath, statbuf, flags) : -1;
}

int access(const char* pathname, int mode) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*, int) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "access");
    return real_fn ? real_fn(rpath, mode) : -1;
}

int faccessat(int dirfd, const char* pathname, int mode, int flags) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(int, const char*, int, int) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "faccessat");
    return real_fn ? real_fn(dirfd, rpath, mode, flags) : -1;
}

// ---------------------------------------------------------------- directories

DIR* opendir(const char* name) {
    char buf[1024];
    const char* rpath = redirect_path(name, buf, sizeof(buf));
    static DIR* (*real_fn)(const char*) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "opendir");
    return real_fn ? real_fn(rpath) : NULL;
}

int chdir(const char* path) {
    char buf[1024];
    const char* rpath = redirect_path(path, buf, sizeof(buf));
    static int (*real_fn)(const char*) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "chdir");
    return real_fn ? real_fn(rpath) : -1;
}

int mkdir(const char* pathname, mode_t mode) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*, mode_t) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "mkdir");
    return real_fn ? real_fn(rpath, mode) : -1;
}

int mkdirat(int dirfd, const char* pathname, mode_t mode) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(int, const char*, mode_t) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "mkdirat");
    return real_fn ? real_fn(dirfd, rpath, mode) : -1;
}

// ---------------------------------------------------------------- readlink / unlink / rename

ssize_t readlink(const char* pathname, char* out_buf, size_t bufsiz) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static ssize_t (*real_fn)(const char*, char*, size_t) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "readlink");
    return real_fn ? real_fn(rpath, out_buf, bufsiz) : -1;
}

ssize_t readlinkat(int dirfd, const char* pathname, char* out_buf, size_t bufsiz) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static ssize_t (*real_fn)(int, const char*, char*, size_t) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "readlinkat");
    return real_fn ? real_fn(dirfd, rpath, out_buf, bufsiz) : -1;
}

int unlink(const char* pathname) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "unlink");
    return real_fn ? real_fn(rpath) : -1;
}

int unlinkat(int dirfd, const char* pathname, int flags) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(int, const char*, int) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "unlinkat");
    return real_fn ? real_fn(dirfd, rpath, flags) : -1;
}

int rename(const char* oldpath, const char* newpath) {
    char buf1[1024], buf2[1024];
    const char* rold = redirect_path(oldpath, buf1, sizeof(buf1));
    const char* rnew = redirect_path(newpath, buf2, sizeof(buf2));
    static int (*real_fn)(const char*, const char*) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "rename");
    return real_fn ? real_fn(rold, rnew) : -1;
}

int chmod(const char* pathname, mode_t mode) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*, mode_t) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "chmod");
    return real_fn ? real_fn(rpath, mode) : -1;
}

// ---------------------------------------------------------------- execve

int execve(const char* filename, char* const argv[], char* const envp[]) {
    char buf[1024];
    const char* rpath = redirect_path(filename, buf, sizeof(buf));
    static int (*real_fn)(const char*, char* const[], char* const[]) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "execve");

    // Check if rpath is a script with a hardcoded Termux shebang
    int fd = open(rpath, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        char header[256];
        ssize_t n = read(fd, header, sizeof(header) - 1);
        close(fd);
        if (n > 2 && header[0] == '#' && header[1] == '!') {
            header[n] = '\0';
            char* newline = strchr(header, '\n');
            if (newline) *newline = '\0';
            char* interp = header + 2;
            while (*interp == ' ') interp++;
            if (strstr(interp, "com.termux/files")) {
                char interp_buf[512];
                const char* real_interp = redirect_path(interp, interp_buf, sizeof(interp_buf));
                // Build new argv: [real_interp, rpath, original_argv[1...]]
                int argc = 0;
                while (argv && argv[argc]) argc++;
                char** new_argv = (char**) malloc(sizeof(char*) * (argc + 2));
                if (new_argv) {
                    new_argv[0] = (char*) real_interp;
                    new_argv[1] = (char*) rpath;
                    for (int i = 1; i <= argc; i++) {
                        new_argv[i + 1] = argv[i];
                    }
                    return real_fn(real_interp, new_argv, envp);
                }
            }
        }
    }

    return real_fn ? real_fn(rpath, argv, envp) : -1;
}

int execv(const char* path, char* const argv[]) {
    extern char** environ;
    return execve(path, argv, environ);
}
