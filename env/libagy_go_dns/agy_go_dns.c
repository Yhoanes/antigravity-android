/*
 * libagy-go-dns.so - Bionic LD_PRELOAD shim for static/pure-Go ARM64 binaries on Android.
 *
 * Official Antigravity plugin CLIs (gdrive, gdocs, gsheets, gslides, gcalendar, gmail, etc.)
 * download static Linux ARM64 Go binaries compiled with CGO_ENABLED=0. On Android, /etc/resolv.conf
 * does not exist, causing Go's pure-Go DNS resolver to fall back to [::1]:53 (connection refused).
 *
 * Placed BEFORE libtermux-exec.so in LD_PRELOAD, this shim intercepts exec* calls:
 * 1. When executing an ARM64 ELF binary under /data/ (outside /files/runtime/) that contains
 *    the 16-byte literal "/etc/resolv.conf", it atomically rewrites those 16 bytes in-place
 *    to "/proc/self/fd/99" (exact same 16-byte length, preserving Go .rodata string tables).
 * 2. Whenever executing a binary containing "/etc/resolv.conf" or "/proc/self/fd/99", it opens
 *    $HOME/etc/resolv.conf (maintained live by NetworkConfigWriter) on FD 99 with FD_CLOEXEC=0
 *    before delegating to RTLD_NEXT (libtermux-exec.so / libc).
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define AGY_DNS_FD 99
static const char NEEDLE[16]  = "/etc/resolv.conf";
static const char PATCHED[16] = "/proc/self/fd/99";

static int write_all(int fd, const uint8_t *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, buf + off, len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        off += (size_t)n;
    }
    return 0;
}

static int open_resolv_conf_fd(const char *target_path) {
    char cand[PATH_MAX];
    struct stat st;

    const char *home = getenv("HOME");
    if (home && home[0] == '/') {
        snprintf(cand, sizeof(cand), "%s/etc/resolv.conf", home);
        if (stat(cand, &st) == 0 && st.st_size > 0) {
            int fd = open(cand, O_RDONLY);
            if (fd >= 0) return fd;
        }
    }

    const char *files_seg = strstr(target_path, "/files/");
    if (files_seg) {
        size_t prefix_len = (size_t)(files_seg - target_path) + 7; /* includes "/files/" */
        if (prefix_len + 20 < sizeof(cand)) {
            memcpy(cand, target_path, prefix_len);
            strcpy(cand + prefix_len, "etc/resolv.conf");
            if (stat(cand, &st) == 0 && st.st_size > 0) {
                int fd = open(cand, O_RDONLY);
                if (fd >= 0) return fd;
            }
        }
    }

    const char *prefix = getenv("PREFIX");
    if (prefix && prefix[0] == '/') {
        snprintf(cand, sizeof(cand), "%s/etc/resolv.conf", prefix);
        if (stat(cand, &st) == 0 && st.st_size > 0) {
            int fd = open(cand, O_RDONLY);
            if (fd >= 0) return fd;
        }
    }

    const char *tmpdir = getenv("TMPDIR");
    if (!tmpdir || tmpdir[0] != '/') {
        tmpdir = "/data/local/tmp";
    }
    snprintf(cand, sizeof(cand), "%s/.agy_go_resolv.conf", tmpdir);
    if (stat(cand, &st) != 0 || st.st_size == 0) {
        int wfd = open(cand, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (wfd >= 0) {
            static const char fallback_dns[] = "nameserver 8.8.8.8\nnameserver 1.1.1.1\n";
            (void)write_all(wfd, (const uint8_t *)fallback_dns, sizeof(fallback_dns) - 1);
            close(wfd);
        }
    }
    return open(cand, O_RDONLY);
}

static void bind_dns_fd99(const char *target_path) {
    int rfd = open_resolv_conf_fd(target_path);
    if (rfd < 0) return;
    if (rfd != AGY_DNS_FD) {
        if (dup2(rfd, AGY_DNS_FD) < 0) {
            close(rfd);
            return;
        }
        close(rfd);
    }
    int flags = fcntl(AGY_DNS_FD, F_GETFD);
    if (flags >= 0 && (flags & FD_CLOEXEC)) {
        (void)fcntl(AGY_DNS_FD, F_SETFD, flags & ~FD_CLOEXEC);
    } else {
        (void)fcntl(AGY_DNS_FD, F_SETFD, 0);
    }
}

static void agy_prepare_path(const char *raw_path) {
    if (!raw_path || raw_path[0] == '\0') return;

    char resolved[PATH_MAX];
    const char *target = raw_path;
    if (realpath(raw_path, resolved) != NULL) {
        target = resolved;
    }

    if (strncmp(target, "/data/", 6) != 0) return;
    if (strstr(target, "/files/runtime/") != NULL) return;

    int fd = open(target, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;

    uint8_t hdr[64];
    ssize_t n = pread(fd, hdr, sizeof(hdr), 0);
    if (n < 64 || hdr[0] != 0x7F || hdr[1] != 'E' || hdr[2] != 'L' || hdr[3] != 'F'
            || hdr[4] != 2 || hdr[5] != 1) {
        close(fd);
        return;
    }

    uint16_t e_machine = (uint16_t)(hdr[18] | (hdr[19] << 8));
    if (e_machine != 0xB7) { /* EM_AARCH64 */
        close(fd);
        return;
    }

    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 65536 || st.st_size > (200L * 1024L * 1024L)) {
        close(fd);
        return;
    }

    /* Fast path: check PT_INTERP in ELF64 program headers. Small dynamic C binaries (<1MB) are skipped. */
    uint64_t e_phoff = 0;
    memcpy(&e_phoff, hdr + 32, sizeof(uint64_t));
    uint16_t e_phentsize = (uint16_t)(hdr[54] | (hdr[55] << 8));
    uint16_t e_phnum = (uint16_t)(hdr[56] | (hdr[57] << 8));
    int has_interp = 0;
    if (e_phoff > 0 && e_phentsize == 56 && e_phnum > 0 && e_phnum <= 32
            && (off_t)(e_phoff + (uint64_t)e_phnum * 56) <= st.st_size) {
        uint8_t phbuf[32 * 56];
        size_t phbytes = (size_t)e_phnum * 56;
        if (pread(fd, phbuf, phbytes, (off_t)e_phoff) == (ssize_t)phbytes) {
            for (uint16_t i = 0; i < e_phnum; i++) {
                uint32_t p_type = 0;
                memcpy(&p_type, phbuf + i * 56, sizeof(uint32_t));
                if (p_type == 3) { /* PT_INTERP */
                    has_interp = 1;
                    break;
                }
            }
        }
    }
    if (has_interp && st.st_size < (1024 * 1024)) {
        close(fd);
        return;
    }

    size_t fsize = (size_t)st.st_size;
    uint8_t *map = (uint8_t *)mmap(NULL, fsize, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return;

    int needs_fd99 = 0;
    uint8_t *hit = (uint8_t *)memmem(map, fsize, NEEDLE, 16);
    if (hit != NULL) {
        needs_fd99 = 1;
        uint8_t *cur = hit;
        size_t rem = fsize - (size_t)(cur - map);
        while (cur != NULL && rem >= 16) {
            memcpy(cur, PATCHED, 16);
            cur += 16;
            rem = fsize - (size_t)(cur - map);
            if (rem < 16) break;
            cur = (uint8_t *)memmem(cur, rem, NEEDLE, 16);
        }

        char tmp_path[PATH_MAX];
        int tn = snprintf(tmp_path, sizeof(tmp_path), "%s.agydns.%d", target, (int)getpid());
        if (tn > 0 && (size_t)tn < sizeof(tmp_path)) {
            int tfd = open(tmp_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0755);
            if (tfd >= 0) {
                if (write_all(tfd, map, fsize) == 0) {
                    (void)fchmod(tfd, 0755);
                    close(tfd);
                    if (rename(tmp_path, target) != 0) {
                        unlink(tmp_path);
                    }
                } else {
                    close(tfd);
                    unlink(tmp_path);
                }
            }
        }
    } else if (memmem(map, fsize, PATCHED, 16) != NULL) {
        needs_fd99 = 1;
    }

    munmap(map, fsize);

    if (needs_fd99) {
        bind_dns_fd99(target);
    }
}

static void agy_prepare_search_path(const char *file, const char *path_env) {
    if (!file || file[0] == '\0') return;
    if (strchr(file, '/') != NULL) {
        agy_prepare_path(file);
        return;
    }
    if (!path_env) path_env = getenv("PATH");
    if (!path_env) return;

    char cand[PATH_MAX];
    const char *p = path_env;
    while (*p) {
        const char *colon = strchr(p, ':');
        size_t dlen = colon ? (size_t)(colon - p) : strlen(p);
        if (dlen > 0 && dlen + 1 + strlen(file) + 1 < sizeof(cand)) {
            memcpy(cand, p, dlen);
            cand[dlen] = '/';
            strcpy(cand + dlen + 1, file);
            if (access(cand, X_OK) == 0) {
                agy_prepare_path(cand);
                return;
            }
        }
        if (!colon) break;
        p = colon + 1;
    }
}

typedef int (*execve_fn_t)(const char *, char *const[], char *const[]);
typedef int (*execv_fn_t)(const char *, char *const[]);
typedef int (*execvp_fn_t)(const char *, char *const[]);
typedef int (*execvpe_fn_t)(const char *, char *const[], char *const[]);

__attribute__((visibility("default")))
int execve(const char *filename, char *const argv[], char *const envp[]) {
    int saved_errno = errno;
    agy_prepare_path(filename);
    execve_fn_t next = (execve_fn_t)dlsym(RTLD_NEXT, "execve");
    errno = saved_errno;
    return next(filename, argv, envp);
}

__attribute__((visibility("default")))
int execv(const char *filename, char *const argv[]) {
    int saved_errno = errno;
    agy_prepare_path(filename);
    execv_fn_t next = (execv_fn_t)dlsym(RTLD_NEXT, "execv");
    errno = saved_errno;
    return next(filename, argv);
}

__attribute__((visibility("default")))
int execvp(const char *file, char *const argv[]) {
    int saved_errno = errno;
    agy_prepare_search_path(file, NULL);
    execvp_fn_t next = (execvp_fn_t)dlsym(RTLD_NEXT, "execvp");
    errno = saved_errno;
    return next(file, argv);
}

__attribute__((visibility("default")))
int execvpe(const char *file, char *const argv[], char *const envp[]) {
    int saved_errno = errno;
    const char *custom_path = NULL;
    if (envp) {
        for (int i = 0; envp[i] != NULL; i++) {
            if (strncmp(envp[i], "PATH=", 5) == 0) {
                custom_path = envp[i] + 5;
                break;
            }
        }
    }
    agy_prepare_search_path(file, custom_path);
    execvpe_fn_t next = (execvpe_fn_t)dlsym(RTLD_NEXT, "execvpe");
    errno = saved_errno;
    return next(file, argv, envp);
}
