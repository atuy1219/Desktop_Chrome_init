#define _GNU_SOURCE 1

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

/*
 * Read-only native diagnostic for the official Google-signed Chrome APK.
 *
 * Unlike Java method hooks, this callback runs in the native linker load
 * sequence, even when Chromium uses a separate TrichromeLibrary APK. The
 * LSPosed native hook API is retained for a future *verified* ARM64 patch.
 *
 * No inline patch is attempted without an exact native binary fingerprint,
 * disassembly-verified target, and a known-safe test environment.
 *
 * Native LSPosed API used here:
 *   https://github.com/LSPosed/LSPosed/blob/master/core/src/main/jni/src/native_api.h
 */

#define TAG "ChromeNativeOmnibox"

typedef int (*HookFunction)(void *target, void *replace, void **original);
typedef int (*UnhookFunction)(void *target);
typedef void (*LibraryLoadedCallback)(const char *name, void *handle);

typedef struct {
    uint32_t version;
    HookFunction hook_func;
    UnhookFunction unhook_func;
} NativeAPIEntries;

static const NativeAPIEntries *g_native_api = NULL;
static bool g_logged_module = false;
static bool g_logged_chromium = false;

static bool is_chrome_process(void) {
    char name[128] = {0};
    FILE *f = fopen("/proc/self/cmdline", "rb");
    if (f == NULL) return false;
    size_t size = fread(name, 1, sizeof(name) - 1, f);
    fclose(f);
    if (size == 0) return false;
    return strcmp(name, "com.android.chrome") == 0;
}

static bool chromium_so_name(const char *name) {
    if (name == NULL) return false;
    return strstr(name, "libmonochrome.so") != NULL
            || strstr(name, "libchrome.so") != NULL
            || strstr(name, "libchromium.so") != NULL;
}

static size_t align4(size_t value) {
    return (value + 3u) & ~((size_t)3u);
}

static bool segment_covers_note(const struct dl_phdr_info *info,
                                uintptr_t note_begin, uintptr_t note_end) {
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) *load = &info->dlpi_phdr[i];
        if (load->p_type != PT_LOAD) continue;
        uintptr_t begin = (uintptr_t)info->dlpi_addr + load->p_vaddr;
        uintptr_t end = begin + load->p_memsz;
        if (note_begin >= begin && note_end <= end) return true;
    }
    return false;
}

static int inspect_elf(const struct dl_phdr_info *info,
                       size_t ignored, void *context) {
    (void)ignored;
    const char *module = (const char *)context;
    if (info->dlpi_name == NULL
            || (module != NULL && strcmp(info->dlpi_name, module) != 0)
            || !chromium_so_name(info->dlpi_name)) {
        return 0;
    }

    char build_id[160] = {0};
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_NOTE || ph->p_memsz < sizeof(ElfW(Nhdr))
                || ph->p_memsz > 65536) continue;
        const uintptr_t start =
                (uintptr_t)info->dlpi_addr + (uintptr_t)ph->p_vaddr;
        const uintptr_t end = start + ph->p_memsz;
        if (end < start || !segment_covers_note(info, start, end)) continue;

        const uint8_t *bytes = (const uint8_t *)start;
        size_t offset = 0;
        while (offset + sizeof(ElfW(Nhdr)) <= ph->p_memsz) {
            ElfW(Nhdr) hdr;
            memcpy(&hdr, bytes + offset, sizeof(hdr));
            offset += sizeof(hdr);
            if (hdr.n_namesz > 128 || hdr.n_descsz > 128) break;
            size_t names = align4(hdr.n_namesz);
            size_t descs = align4(hdr.n_descsz);
            if (offset + names + descs > ph->p_memsz) break;
            const uint8_t *n = bytes + offset;
            const uint8_t *desc = n + names;
            if (hdr.n_type == NT_GNU_BUILD_ID && hdr.n_namesz == 4
                    && memcmp(n, "GNU", 4) == 0) {
                size_t nbytes = hdr.n_descsz;
                if (nbytes > 32) nbytes = 32;
                for (size_t j = 0; j < nbytes; ++j) {
                    snprintf(build_id + 2*j,
                            sizeof(build_id) - 2*j, "%02x", desc[j]);
                }
                break;
            }
            offset += names + descs;
        }
        if (build_id[0]) break;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
            "mapped=%s base=0x%llx buildId=%s phdrCount=%u",
            info->dlpi_name, (unsigned long long)info->dlpi_addr,
            build_id[0] ? build_id : "NOT_FOUND",
            (unsigned)info->dlpi_phnum);
    return 0;
}

static void on_library_loaded(const char *name, void *handle) {
    if (!is_chrome_process()) return;
    if (!chromium_so_name(name)) return;
    if (g_logged_chromium) return;
    g_logged_chromium = true;

    __android_log_print(ANDROID_LOG_INFO, TAG,
            "Chromium native ELF loaded name=%s handle=%s API=%u",
            name, handle ? "yes" : "null",
            g_native_api ? g_native_api->version : 0);
    dl_iterate_phdr(inspect_elf, NULL);

    /*
     * On official Chrome Android, internal C++ symbols are usually stripped.
     * Checking does not modify memory, whereas installing a hook at a guessed
     * address would risk crashing Chrome. A successful dlsym result is logged
     * for a later, fingerprint-verified pass-through native hook.
     */
    static const char symbol[] =
            "_ZN32ZeroSuggestVerbatimMatchProvider5StartERK17AutocompleteInputb";
    void *function = handle ? dlsym(handle, symbol) : NULL;
    __android_log_print(ANDROID_LOG_INFO, TAG,
            "ZeroSuggestVerbatimMatchProvider::Start exported=%s"
            " (no hook installed; native gate remains unchanged)",
            function != NULL ? "true" : "false");
}

__attribute__((visibility("default"), used))
LibraryLoadedCallback native_init(const NativeAPIEntries *api) {
    g_native_api = api;
    if (!g_logged_module) {
        g_logged_module = true;
        __android_log_print(ANDROID_LOG_INFO, TAG,
                "LSPosed native_init initialized API=%u hookAvailable=%s"
                " pid=%ld mode=DIAGNOSTIC_ONLY",
                api ? api->version : 0,
                api && api->hook_func ? "true" : "false",
                (long)getpid());
    }
    return on_library_loaded;
}
