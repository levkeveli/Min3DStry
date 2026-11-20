/* TeaVM filesystem entry points used by Arc Fi on the 3DS SD card. */
#include "file.h"
#include "date.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int32_t m3ds_file_directory(char* path, char16_t** result) {
    int32_t length;
    *result = teavm_mbToChar16(path, &length);
    return length;
}

int32_t teavm_file_homeDirectory(char16_t** result) {
    return m3ds_file_directory("/3ds/min3dstry", result);
}

int32_t teavm_file_tempDirectory(char16_t** result) {
    return m3ds_file_directory("/3ds/min3dstry/tmp", result);
}

int32_t teavm_file_workDirectory(char16_t** result) {
    char path[PATH_MAX];
    int32_t length;
    if (getcwd(path, sizeof(path)) == NULL) {
        *result = teavm_mbToChar16(strerror(errno), &length);
        return -1 - length;
    }
    return m3ds_file_directory(path, result);
}

static int m3ds_file_stat(char16_t* name, int32_t name_size, struct stat* info) {
    char* path = teavm_char16ToMb(name, name_size);
    if (path == NULL) {
        return -1;
    }
    int result = stat(path, info);
    free(path);
    return result;
}

int32_t teavm_file_isDir(char16_t* name, int32_t name_size) {
    struct stat info;
    return m3ds_file_stat(name, name_size, &info) == 0 && S_ISDIR(info.st_mode);
}

int32_t teavm_file_isFile(char16_t* name, int32_t name_size) {
    struct stat info;
    return m3ds_file_stat(name, name_size, &info) == 0 && S_ISREG(info.st_mode);
}

int32_t teavm_file_length(char16_t* name, int32_t name_size) {
    struct stat info;
    if (m3ds_file_stat(name, name_size, &info) != 0 || !S_ISREG(info.st_mode) || info.st_size < 0) {
        return 0;
    }
    return info.st_size > INT32_MAX ? INT32_MAX : (int32_t)info.st_size;
}

int64_t teavm_file_lastModified(char16_t* name, int32_t name_size) {
    struct stat info;
    return m3ds_file_stat(name, name_size, &info) == 0 ? teavm_date_timeToTimestamp(info.st_mtime) : 0;
}

int32_t teavm_file_delete(char16_t* name, int32_t name_size) {
    char* path = teavm_char16ToMb(name, name_size);
    if (path == NULL) {
        return 0;
    }
    int removed = remove(path) == 0;
    free(path);
    return removed;
}

TeaVM_StringList* teavm_file_listFiles(char16_t* name, int32_t name_size) {
    char* path = teavm_char16ToMb(name, name_size);
    if (path == NULL) {
        return NULL;
    }
    DIR* directory = opendir(path);
    free(path);
    if (directory == NULL) {
        return NULL;
    }

    TeaVM_StringList* entries = teavm_appendString(NULL, NULL, 0);
    while (entries != NULL) {
        errno = 0;
        const struct dirent* entry = readdir(directory);
        if (entry == NULL && errno == 0) {
            break;
        }
        if (entry == NULL) {
            teavm_disposeStringList(entries);
            entries = NULL;
            break;
        }
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }
        int32_t length;
        char16_t* filename = teavm_mbToChar16(entry->d_name, &length);
        if (filename == NULL) {
            teavm_disposeStringList(entries);
            entries = NULL;
            break;
        }
        entries = teavm_appendString(entries, filename, length);
        if (entries == NULL) {
            free(filename);
        }
    }
    closedir(directory);
    return entries;
}

int32_t teavm_file_isWindows(void) {
    return 0;
}

int32_t teavm_file_isCaseSensitive(void) {
    return 0;
}

int32_t teavm_file_createDirectory(char16_t* name, int32_t length) {
    char* path = teavm_char16ToMb(name, length);
    if (path == NULL) {
        return 0;
    }
    int result = mkdir(path, 0777);
    free(path);
    return result == 0;
}

int32_t teavm_file_createFile(char16_t* name, int32_t length) {
    char* path = teavm_char16ToMb(name, length);
    if (path == NULL) {
        return 2;
    }
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL, 0666);
    int error = errno;
    if (fd < 0) {
        // Some SD archive errors for an existing entry are translated to EIO.
        // Preserve TeaVM's distinction between "already exists" and IO failure.
        struct stat info;
        int exists = error == EEXIST || stat(path, &info) == 0;
        free(path);
        return exists ? 1 : 2;
    }
    free(path);
    return close(fd) == 0 ? 0 : 2;
}

int32_t teavm_file_canonicalize(char16_t* path, int32_t length, char16_t** result) {
    (void)path;
    (void)length;
    // Keep TeaVM's Java-level path normalization on the SD adapter.
    *result = NULL;
    return -1;
}

int64_t teavm_file_open(char16_t* name, int32_t name_size, int32_t mode) {
    const char* stream_mode;
    switch (mode) {
        case 1:
            stream_mode = "rb";
            break;
        case 2:
            stream_mode = "wb";
            break;
        case 3:
        case 6:
        case 7:
            stream_mode = "r+b";
            break;
        default:
            return 0;
    }
    char* path = teavm_char16ToMb(name, name_size);
    if (path == NULL) {
        return 0;
    }
    FILE* stream = fopen(path, stream_mode);
    free(path);
    return (int64_t)(intptr_t)stream;
}

int32_t teavm_file_close(int64_t file) {
    return fclose((FILE*)(intptr_t)file) == 0;
}

int32_t teavm_file_flush(int64_t file) {
    return fflush((FILE*)(intptr_t)file) == 0;
}

int32_t teavm_file_seek(int64_t file, int32_t where, int32_t offset) {
    const int origins[] = {SEEK_SET, SEEK_CUR, SEEK_END};
    if (where < 0 || where >= (int)(sizeof(origins) / sizeof(origins[0]))) {
        return 0;
    }
    return fseek((FILE*)(intptr_t)file, offset, origins[where]) == 0;
}

int32_t teavm_file_tell(int64_t file) {
    long position = ftell((FILE*)(intptr_t)file);
    return position > INT32_MAX ? -1 : (int32_t)position;
}

int32_t teavm_file_read(int64_t file, int8_t* data, int32_t offset, int32_t size) {
    return (int32_t)fread(data + offset, 1, (size_t)size, (FILE*)(intptr_t)file);
}

int32_t teavm_file_write(int64_t file, int8_t* data, int32_t offset, int32_t size) {
    return (int32_t)fwrite(data + offset, 1, (size_t)size, (FILE*)(intptr_t)file);
}
