#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/statvfs.h>
#include <sys/vfs.h>
#include <utime.h>
#include <sys/time.h>
#include <unistd.h>
#include <dirent.h>
#include <spawn.h>

static const char* const kTargetPrefixes[] = {
    "/data/data/com.termux/files",
    "/data/user/0/com.termux/files",
    "/data/data/com.bomax.talha.bdcteator/files",
    "/data/user/0/com.bomax.talha.bdcteator/files",
    "/data/data/com.bomax.talha.bdcreator/files",
    "/data/user/0/com.bomax.talha.bdcreator/files",
    "/data/data/com.boomaux.boom.bdcreator/files",
    "/data/user/0/com.boomaux.boom.bdcreator/files",
    "/data/data/com.example.termuxlite/files",
    "/data/user/0/com.example.termuxlite/files",
    NULL
};

static const char* const kTargetAppDirs[] = {
    "/data/data/com.termux",
    "/data/user/0/com.termux",
    "/data/data/com.bomax.talha.bdcteator",
    "/data/user/0/com.bomax.talha.bdcteator",
    "/data/data/com.bomax.talha.bdcreator",
    "/data/user/0/com.bomax.talha.bdcreator",
    "/data/data/com.boomaux.boom.bdcreator",
    "/data/user/0/com.boomaux.boom.bdcreator",
    "/data/data/com.example.termuxlite",
    "/data/user/0/com.example.termuxlite",
    NULL
};

static char g_real_files[512] = {0};
static char g_real_app[512] = {0};
static char g_real_prefix[512] = {0};
static int g_initialized = 0;

static void init_redirect_paths(void) {
    if (g_initialized) return;

    const char* env_files = getenv("BOOMAUX_FILES");
    if (!env_files || !env_files[0]) {
        env_files = getenv("BOOMAXE_FILES");
    }
    if (env_files && env_files[0]) {
        strncpy(g_real_files, env_files, sizeof(g_real_files) - 1);
    } else {
        const char* prefix = getenv("PREFIX");
        if (prefix && prefix[0]) {
            char temp[512];
            strncpy(temp, prefix, sizeof(temp) - 1);
            char* usr_part = strstr(temp, "/usr");
            if (usr_part) *usr_part = '\0';
            strncpy(g_real_files, temp, sizeof(g_real_files) - 1);
        } else {
            // Default package path
            strncpy(g_real_files, "/data/data/com.boomaux.boom.bdcreator/files", sizeof(g_real_files) - 1);
        }
    }

    if (g_real_files[0]) {
        strncpy(g_real_app, g_real_files, sizeof(g_real_app) - 1);
        char* files_part = strstr(g_real_app, "/files");
        if (files_part) *files_part = '\0';

        snprintf(g_real_prefix, sizeof(g_real_prefix), "%s/usr", g_real_files);
    }

    g_initialized = 1;
}

static const char* redirect_path(const char* path, char* buffer, size_t bufsize) {
    if (!path) return path;
    init_redirect_paths();
    if (!g_real_files[0]) return path;

    // 1. Check known prefixes
    for (int i = 0; kTargetPrefixes[i] != NULL; i++) {
        size_t len = strlen(kTargetPrefixes[i]);
        if (strncmp(path, kTargetPrefixes[i], len) == 0) {
            snprintf(buffer, bufsize, "%s%s", g_real_files, path + len);
            return buffer;
        }
    }

    for (int i = 0; kTargetAppDirs[i] != NULL; i++) {
        size_t len = strlen(kTargetAppDirs[i]);
        if (strncmp(path, kTargetAppDirs[i], len) == 0) {
            snprintf(buffer, bufsize, "%s%s", g_real_app, path + len);
            return buffer;
        }
    }

    // 2. Redirect standard virtual Unix paths that Android lacks
    if (strcmp(path, "/bin/sh") == 0 || strcmp(path, "/usr/bin/sh") == 0) {
        snprintf(buffer, bufsize, "%s/bin/sh", g_real_prefix);
        return buffer;
    }
    if (strcmp(path, "/bin/bash") == 0 || strcmp(path, "/usr/bin/bash") == 0) {
        snprintf(buffer, bufsize, "%s/bin/bash", g_real_prefix);
        return buffer;
    }
    if (strcmp(path, "/usr/bin/env") == 0 || strcmp(path, "/bin/env") == 0) {
        snprintf(buffer, bufsize, "%s/bin/env", g_real_prefix);
        return buffer;
    }
    if (strcmp(path, "/tmp") == 0 || strcmp(path, "/tmp/") == 0) {
        snprintf(buffer, bufsize, "%s/tmp", g_real_prefix);
        return buffer;
    }
    if (strncmp(path, "/tmp/", 5) == 0) {
        snprintf(buffer, bufsize, "%s/tmp/%s", g_real_prefix, path + 5);
        return buffer;
    }
    if (strcmp(path, "/var/tmp") == 0 || strcmp(path, "/var/tmp/") == 0) {
        snprintf(buffer, bufsize, "%s/var/tmp", g_real_prefix);
        return buffer;
    }
    if (strncmp(path, "/var/tmp/", 9) == 0) {
        snprintf(buffer, bufsize, "%s/var/tmp/%s", g_real_prefix, path + 9);
        return buffer;
    }

    return path;
}

// ---------------------------------------------------------------- open / openat / creat

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

int open64(const char* pathname, int flags, ...) {
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
    if (!real_fn) real_fn = (open_func) dlsym(RTLD_NEXT, "open64");
    if (!real_fn) real_fn = (open_func) dlsym(RTLD_NEXT, "open");
    return real_fn ? real_fn(rpath, flags, mode) : -1;
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

int openat64(int dirfd, const char* pathname, int flags, ...) {
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
    if (!real_fn) real_fn = (openat_func) dlsym(RTLD_NEXT, "openat64");
    if (!real_fn) real_fn = (openat_func) dlsym(RTLD_NEXT, "openat");
    return real_fn ? real_fn(dirfd, rpath, flags, mode) : -1;
}

int creat(const char* pathname, mode_t mode) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*, mode_t) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, mode_t)) dlsym(RTLD_NEXT, "creat");
    return real_fn ? real_fn(rpath, mode) : -1;
}

int creat64(const char* pathname, mode_t mode) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*, mode_t) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, mode_t)) dlsym(RTLD_NEXT, "creat64");
    if (!real_fn) real_fn = (int (*)(const char*, mode_t)) dlsym(RTLD_NEXT, "creat");
    return real_fn ? real_fn(rpath, mode) : -1;
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

FILE* freopen(const char* pathname, const char* mode, FILE* stream) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static FILE* (*real_fn)(const char*, const char*, FILE*) = NULL;
    if (!real_fn) real_fn = (FILE* (*)(const char*, const char*, FILE*)) dlsym(RTLD_NEXT, "freopen");
    return real_fn ? real_fn(rpath, mode, stream) : NULL;
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

// ---------------------------------------------------------------- directories & scandir

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

int rmdir(const char* pathname) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "rmdir");
    return real_fn ? real_fn(rpath) : -1;
}

// Critical: scandir / scandir64 / scandirat / scandirat64
int scandir(const char* dirp, struct dirent*** namelist,
            int (*filter)(const struct dirent*),
            int (*compar)(const struct dirent**, const struct dirent**)) {
    char buf[1024];
    const char* rpath = redirect_path(dirp, buf, sizeof(buf));
    static int (*real_fn)(const char*, struct dirent***,
                          int (*)(const struct dirent*),
                          int (*)(const struct dirent**, const struct dirent**)) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "scandir");
    return real_fn ? real_fn(rpath, namelist, filter, compar) : -1;
}

int scandir64(const char* dirp, struct dirent64*** namelist,
              int (*filter)(const struct dirent64*),
              int (*compar)(const struct dirent64**, const struct dirent64**)) {
    char buf[1024];
    const char* rpath = redirect_path(dirp, buf, sizeof(buf));
    static int (*real_fn)(const char*, struct dirent64***,
                          int (*)(const struct dirent64*),
                          int (*)(const struct dirent64**, const struct dirent64**)) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "scandir64");
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "scandir");
    return real_fn ? real_fn(rpath, namelist, filter, compar) : -1;
}

int scandirat(int dirfd, const char* dirp, struct dirent*** namelist,
              int (*filter)(const struct dirent*),
              int (*compar)(const struct dirent**, const struct dirent**)) {
    char buf[1024];
    const char* rpath = redirect_path(dirp, buf, sizeof(buf));
    static int (*real_fn)(int, const char*, struct dirent***,
                          int (*)(const struct dirent*),
                          int (*)(const struct dirent**, const struct dirent**)) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "scandirat");
    return real_fn ? real_fn(dirfd, rpath, namelist, filter, compar) : -1;
}

int scandirat64(int dirfd, const char* dirp, struct dirent64*** namelist,
                int (*filter)(const struct dirent64*),
                int (*compar)(const struct dirent64**, const struct dirent64**)) {
    char buf[1024];
    const char* rpath = redirect_path(dirp, buf, sizeof(buf));
    static int (*real_fn)(int, const char*, struct dirent64***,
                          int (*)(const struct dirent64*),
                          int (*)(const struct dirent64**, const struct dirent64**)) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "scandirat64");
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "scandirat");
    return real_fn ? real_fn(dirfd, rpath, namelist, filter, compar) : -1;
}

// ---------------------------------------------------------------- readlink / unlink / rename / symlink

ssize_t readlink(const char* pathname, char* out_buf, size_t bufsiz) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static ssize_t (*real_fn)(const char*, char*, size_t) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "readlink");
    ssize_t ret = real_fn ? real_fn(rpath, out_buf, bufsiz) : -1;
    if (ret > 0 && (size_t) ret < bufsiz) {
        out_buf[ret] = '\0';
        char rewritten[1024];
        const char* red = redirect_path(out_buf, rewritten, sizeof(rewritten));
        if (red != out_buf) {
            size_t new_len = strlen(red);
            if (new_len < bufsiz) {
                memcpy(out_buf, red, new_len + 1);
                ret = (ssize_t) new_len;
            }
        }
    }
    return ret;
}

ssize_t readlinkat(int dirfd, const char* pathname, char* out_buf, size_t bufsiz) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static ssize_t (*real_fn)(int, const char*, char*, size_t) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "readlinkat");
    ssize_t ret = real_fn ? real_fn(dirfd, rpath, out_buf, bufsiz) : -1;
    if (ret > 0 && (size_t) ret < bufsiz) {
        out_buf[ret] = '\0';
        char rewritten[1024];
        const char* red = redirect_path(out_buf, rewritten, sizeof(rewritten));
        if (red != out_buf) {
            size_t new_len = strlen(red);
            if (new_len < bufsiz) {
                memcpy(out_buf, red, new_len + 1);
                ret = (ssize_t) new_len;
            }
        }
    }
    return ret;
}

char* realpath(const char* path, char* resolved_path) {
    char buf[1024];
    const char* rpath = redirect_path(path, buf, sizeof(buf));
    static char* (*real_fn)(const char*, char*) = NULL;
    if (!real_fn) real_fn = (char* (*)(const char*, char*)) dlsym(RTLD_NEXT, "realpath");
    char* res = real_fn ? real_fn(rpath, resolved_path) : NULL;
    if (res != NULL) {
        char temp[1024];
        const char* red = redirect_path(res, temp, sizeof(temp));
        if (red != res) {
            strcpy(res, red);
        }
    }
    return res;
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

int remove(const char* pathname) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*)) dlsym(RTLD_NEXT, "remove");
    return real_fn ? real_fn(rpath) : -1;
}

int rename(const char* oldpath, const char* newpath) {
    char buf1[1024], buf2[1024];
    const char* rold = redirect_path(oldpath, buf1, sizeof(buf1));
    const char* rnew = redirect_path(newpath, buf2, sizeof(buf2));
    static int (*real_fn)(const char*, const char*) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, const char*)) dlsym(RTLD_NEXT, "rename");
    return real_fn ? real_fn(rold, rnew) : -1;
}

int renameat(int olddirfd, const char* oldpath, int newdirfd, const char* newpath) {
    char buf1[1024], buf2[1024];
    const char* rold = redirect_path(oldpath, buf1, sizeof(buf1));
    const char* rnew = redirect_path(newpath, buf2, sizeof(buf2));
    static int (*real_fn)(int, const char*, int, const char*) = NULL;
    if (!real_fn) real_fn = (int (*)(int, const char*, int, const char*)) dlsym(RTLD_NEXT, "renameat");
    return real_fn ? real_fn(olddirfd, rold, newdirfd, rnew) : -1;
}

int symlink(const char* target, const char* linkpath) {
    char buf1[1024], buf2[1024];
    const char* rtarget = redirect_path(target, buf1, sizeof(buf1));
    const char* rlink = redirect_path(linkpath, buf2, sizeof(buf2));
    static int (*real_fn)(const char*, const char*) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, const char*)) dlsym(RTLD_NEXT, "symlink");
    return real_fn ? real_fn(rtarget, rlink) : -1;
}

int symlinkat(const char* target, int newdirfd, const char* linkpath) {
    char buf1[1024], buf2[1024];
    const char* rtarget = redirect_path(target, buf1, sizeof(buf1));
    const char* rlink = redirect_path(linkpath, buf2, sizeof(buf2));
    static int (*real_fn)(const char*, int, const char*) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, int, const char*)) dlsym(RTLD_NEXT, "symlinkat");
    return real_fn ? real_fn(rtarget, newdirfd, rlink) : -1;
}

int link(const char* oldpath, const char* newpath) {
    char buf1[1024], buf2[1024];
    const char* rold = redirect_path(oldpath, buf1, sizeof(buf1));
    const char* rnew = redirect_path(newpath, buf2, sizeof(buf2));
    static int (*real_fn)(const char*, const char*) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, const char*)) dlsym(RTLD_NEXT, "link");
    return real_fn ? real_fn(rold, rnew) : -1;
}

int linkat(int olddirfd, const char* oldpath, int newdirfd, const char* newpath, int flags) {
    char buf1[1024], buf2[1024];
    const char* rold = redirect_path(oldpath, buf1, sizeof(buf1));
    const char* rnew = redirect_path(newpath, buf2, sizeof(buf2));
    static int (*real_fn)(int, const char*, int, const char*, int) = NULL;
    if (!real_fn) real_fn = (int (*)(int, const char*, int, const char*, int)) dlsym(RTLD_NEXT, "linkat");
    return real_fn ? real_fn(olddirfd, rold, newdirfd, rnew, flags) : -1;
}

// ---------------------------------------------------------------- chmod / chown / truncate / timestamps

int chmod(const char* pathname, mode_t mode) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*, mode_t) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, mode_t)) dlsym(RTLD_NEXT, "chmod");
    return real_fn ? real_fn(rpath, mode) : -1;
}

int fchmodat(int dirfd, const char* pathname, mode_t mode, int flags) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(int, const char*, mode_t, int) = NULL;
    if (!real_fn) real_fn = (int (*)(int, const char*, mode_t, int)) dlsym(RTLD_NEXT, "fchmodat");
    return real_fn ? real_fn(dirfd, rpath, mode, flags) : -1;
}

int chown(const char* pathname, uid_t owner, gid_t group) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*, uid_t, gid_t) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, uid_t, gid_t)) dlsym(RTLD_NEXT, "chown");
    return real_fn ? real_fn(rpath, owner, group) : -1;
}

int lchown(const char* pathname, uid_t owner, gid_t group) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*, uid_t, gid_t) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, uid_t, gid_t)) dlsym(RTLD_NEXT, "lchown");
    return real_fn ? real_fn(rpath, owner, group) : -1;
}

int fchownat(int dirfd, const char* pathname, uid_t owner, gid_t group, int flags) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(int, const char*, uid_t, gid_t, int) = NULL;
    if (!real_fn) real_fn = (int (*)(int, const char*, uid_t, gid_t, int)) dlsym(RTLD_NEXT, "fchownat");
    return real_fn ? real_fn(dirfd, rpath, owner, group, flags) : -1;
}

int truncate(const char* pathname, off_t length) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*, off_t) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, off_t)) dlsym(RTLD_NEXT, "truncate");
    return real_fn ? real_fn(rpath, length) : -1;
}

int truncate64(const char* pathname, off64_t length) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(const char*, off64_t) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, off64_t)) dlsym(RTLD_NEXT, "truncate64");
    if (!real_fn) real_fn = (int (*)(const char*, off64_t)) dlsym(RTLD_NEXT, "truncate");
    return real_fn ? real_fn(rpath, length) : -1;
}

int utime(const char* filename, const struct utimbuf* times) {
    char buf[1024];
    const char* rpath = redirect_path(filename, buf, sizeof(buf));
    static int (*real_fn)(const char*, const struct utimbuf*) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, const struct utimbuf*)) dlsym(RTLD_NEXT, "utime");
    return real_fn ? real_fn(rpath, times) : -1;
}

int utimes(const char* filename, const struct timeval times[2]) {
    char buf[1024];
    const char* rpath = redirect_path(filename, buf, sizeof(buf));
    static int (*real_fn)(const char*, const struct timeval[2]) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, const struct timeval[2])) dlsym(RTLD_NEXT, "utimes");
    return real_fn ? real_fn(rpath, times) : -1;
}

int utimensat(int dirfd, const char* pathname, const struct timespec times[2], int flags) {
    char buf[1024];
    const char* rpath = redirect_path(pathname, buf, sizeof(buf));
    static int (*real_fn)(int, const char*, const struct timespec[2], int) = NULL;
    if (!real_fn) real_fn = (int (*)(int, const char*, const struct timespec[2], int)) dlsym(RTLD_NEXT, "utimensat");
    return real_fn ? real_fn(dirfd, rpath, times, flags) : -1;
}

int statvfs(const char* path, struct statvfs* buf) {
    char b[1024];
    const char* rpath = redirect_path(path, b, sizeof(b));
    static int (*real_fn)(const char*, struct statvfs*) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, struct statvfs*)) dlsym(RTLD_NEXT, "statvfs");
    return real_fn ? real_fn(rpath, buf) : -1;
}

int statfs(const char* path, struct statfs* buf) {
    char b[1024];
    const char* rpath = redirect_path(path, b, sizeof(b));
    static int (*real_fn)(const char*, struct statfs*) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, struct statfs*)) dlsym(RTLD_NEXT, "statfs");
    return real_fn ? real_fn(rpath, buf) : -1;
}

// ---------------------------------------------------------------- execve / execv / execvp / execvpe / posix_spawn

static int check_script_shebang(const char* rpath, char* interp_buf, size_t interp_buf_size, char** shebang_arg_out) {
    int fd = open(rpath, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;

    char header[256];
    ssize_t n = read(fd, header, sizeof(header) - 1);
    close(fd);
    if (n <= 2 || header[0] != '#' || header[1] != '!') {
        return 0;
    }

    header[n] = '\0';
    char* newline = strchr(header, '\n');
    if (newline) *newline = '\0';
    char* interp = header + 2;
    while (*interp == ' ' || *interp == '\t') interp++;
    char* shebang_arg = NULL;
    char* space = strpbrk(interp, " \t");
    if (space) {
        *space = '\0';
        shebang_arg = space + 1;
        while (*shebang_arg == ' ' || *shebang_arg == '\t') shebang_arg++;
        if (!*shebang_arg) shebang_arg = NULL;
    }

    init_redirect_paths();

    const char* real_interp = interp;
    char temp[512];
    if (strstr(interp, "com.termux") || strstr(interp, "com.bomax") ||
        strstr(interp, "com.boomaux") || strstr(interp, "com.example.termuxlite")) {
        real_interp = redirect_path(interp, temp, sizeof(temp));
    } else if (strcmp(interp, "/bin/sh") == 0 || strcmp(interp, "/usr/bin/sh") == 0) {
        if (g_real_prefix[0]) {
            snprintf(temp, sizeof(temp), "%s/bin/sh", g_real_prefix);
            real_interp = temp;
        }
    } else if (strcmp(interp, "/bin/bash") == 0 || strcmp(interp, "/usr/bin/bash") == 0) {
        if (g_real_prefix[0]) {
            snprintf(temp, sizeof(temp), "%s/bin/bash", g_real_prefix);
            real_interp = temp;
        }
    } else if (strcmp(interp, "/usr/bin/env") == 0 || strcmp(interp, "/bin/env") == 0) {
        if (g_real_prefix[0]) {
            snprintf(temp, sizeof(temp), "%s/bin/env", g_real_prefix);
            real_interp = temp;
        }
    }

    strncpy(interp_buf, real_interp, interp_buf_size - 1);
    interp_buf[interp_buf_size - 1] = '\0';
    if (shebang_arg_out) *shebang_arg_out = shebang_arg ? strdup(shebang_arg) : NULL;
    return 1;
}

int execve(const char* filename, char* const argv[], char* const envp[]) {
    char buf[1024];
    const char* rpath = redirect_path(filename, buf, sizeof(buf));
    static int (*real_fn)(const char*, char* const[], char* const[]) = NULL;
    if (!real_fn) real_fn = (int (*)(const char*, char* const[], char* const[])) dlsym(RTLD_NEXT, "execve");

    char interp_buf[512] = {0};
    char* shebang_arg = NULL;
    if (check_script_shebang(rpath, interp_buf, sizeof(interp_buf), &shebang_arg)) {
        int argc = 0;
        while (argv && argv[argc]) argc++;
        int extra = shebang_arg ? 3 : 2;
        char** new_argv = (char**) malloc(sizeof(char*) * (argc + extra));
        if (new_argv) {
            int idx = 0;
            new_argv[idx++] = interp_buf;
            if (shebang_arg) new_argv[idx++] = shebang_arg;
            new_argv[idx++] = (char*) rpath;
            for (int i = 1; i <= argc; i++) {
                new_argv[idx++] = argv[i];
            }
            int res = real_fn ? real_fn(interp_buf, new_argv, envp) : -1;
            free(shebang_arg);
            free(new_argv);
            return res;
        }
        if (shebang_arg) free(shebang_arg);
    }

    return real_fn ? real_fn(rpath, argv, envp) : -1;
}

int execv(const char* path, char* const argv[]) {
    extern char** environ;
    return execve(path, argv, environ);
}

int execvp(const char* file, char* const argv[]) {
    extern char** environ;
    char buf[1024];
    const char* rpath = redirect_path(file, buf, sizeof(buf));
    return execve(rpath, argv, environ);
}

int execvpe(const char* file, char* const argv[], char* const envp[]) {
    char buf[1024];
    const char* rpath = redirect_path(file, buf, sizeof(buf));
    return execve(rpath, argv, envp);
}

typedef int (*posix_spawn_fn)(pid_t*, const char*, const posix_spawn_file_actions_t*,
                              const posix_spawnattr_t*, char* const[], char* const[]);

int posix_spawn(pid_t* pid, const char* path, const posix_spawn_file_actions_t* file_actions,
                const posix_spawnattr_t* attr, char* const argv[], char* const envp[]) {
    char buf[1024];
    const char* rpath = redirect_path(path, buf, sizeof(buf));
    static posix_spawn_fn real_fn = NULL;
    if (!real_fn) real_fn = (posix_spawn_fn) dlsym(RTLD_NEXT, "posix_spawn");

    char interp_buf[512] = {0};
    char* shebang_arg = NULL;
    if (check_script_shebang(rpath, interp_buf, sizeof(interp_buf), &shebang_arg)) {
        int argc = 0;
        while (argv && argv[argc]) argc++;
        int extra = shebang_arg ? 3 : 2;
        char** new_argv = (char**) malloc(sizeof(char*) * (argc + extra));
        if (new_argv) {
            int idx = 0;
            new_argv[idx++] = interp_buf;
            if (shebang_arg) new_argv[idx++] = shebang_arg;
            new_argv[idx++] = (char*) rpath;
            for (int i = 1; i <= argc; i++) {
                new_argv[idx++] = argv[i];
            }
            int res;
            if (real_fn) {
                res = real_fn(pid, interp_buf, file_actions, attr, new_argv, envp);
            } else {
                pid_t c = fork();
                if (c == 0) {
                    execve(interp_buf, new_argv, envp);
                    _exit(127);
                } else if (c > 0) {
                    if (pid) *pid = c;
                    res = 0;
                } else {
                    res = -1;
                }
            }
            free(shebang_arg);
            free(new_argv);
            return res;
        }
        if (shebang_arg) free(shebang_arg);
    }

    if (real_fn) return real_fn(pid, rpath, file_actions, attr, argv, envp);

    pid_t c = fork();
    if (c == 0) {
        execve(rpath, argv, envp);
        _exit(127);
    } else if (c > 0) {
        if (pid) *pid = c;
        return 0;
    }
    return -1;
}

int posix_spawnp(pid_t* pid, const char* file, const posix_spawn_file_actions_t* file_actions,
                 const posix_spawnattr_t* attr, char* const argv[], char* const envp[]) {
    char buf[1024];
    const char* rpath = redirect_path(file, buf, sizeof(buf));
    return posix_spawn(pid, rpath, file_actions, attr, argv, envp);
}
