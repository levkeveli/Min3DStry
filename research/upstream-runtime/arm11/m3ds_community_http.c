#include "m3ds_community_http.h"
#include "net_3ds.h"

#include <3ds.h>
#include <curl/curl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_DIRECTORY_BYTES (256U * 1024U)

typedef struct {
    char url[256];
    uint8_t *body;
    size_t used;
    bool success;
} DirectoryRequest;

static DirectoryRequest request;
static Thread worker;

static size_t append_body(char *bytes, size_t size, size_t count, void *context) {
    DirectoryRequest *download = context;
    if (size != 0 && count > (MAX_DIRECTORY_BYTES - download->used) / size) return 0;
    size_t length = size * count;
    memcpy(download->body + download->used, bytes, length);
    download->used += length;
    return length;
}

static void fetch_directory(void *context) {
    DirectoryRequest *download = context;
    M3dsNet3dsContext soc;
    M3dsNetTransport transport;
    if (!m3ds_net_3ds_init(&transport, &soc)) return;
    if (curl_global_init(CURL_GLOBAL_DEFAULT) == CURLE_OK) {
        CURL *curl = curl_easy_init();
        if (curl) {
            curl_easy_setopt(curl, CURLOPT_URL, download->url);
            curl_easy_setopt(curl, CURLOPT_PROTOCOLS_STR, "https");
            curl_easy_setopt(curl, CURLOPT_CAINFO, "romfs:/community-ca.pem");
            curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 1L);
            curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 2L);
            curl_easy_setopt(curl, CURLOPT_FAILONERROR, 1L);
            curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
            curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT_MS, 5000L);
            curl_easy_setopt(curl, CURLOPT_TIMEOUT_MS, 12000L);
            curl_easy_setopt(curl, CURLOPT_USERAGENT, "Min3DStry");
            curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, append_body);
            curl_easy_setopt(curl, CURLOPT_WRITEDATA, download);
            CURLcode result = curl_easy_perform(curl);
            long status = 0;
            curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
            download->success = result == CURLE_OK && status == 200 && download->used > 0;
            if (!download->success) {
                char marker[96];
                int length = snprintf(marker, sizeof(marker),
                    "UPSTREAM_COMMUNITY_DIRECTORY curl=%d http=%ld", (int)result, status);
                if (length > 0 && (size_t)length < sizeof(marker)) svcOutputDebugString(marker, length);
            }
            curl_easy_cleanup(curl);
        }
        curl_global_cleanup();
    }
    m3ds_net_3ds_exit(&soc);
}

int32_t m3ds_community_http_start(const uint8_t *url, int32_t length) {
    if (worker || !url || length <= 8 || (size_t)length >= sizeof(request.url)
            || memcmp(url, "https://", 8) != 0
            || memchr(url, '\0', (size_t)length)) return 0;
    int32_t priority;
    if (R_FAILED(svcGetThreadPriority(&priority, CUR_THREAD_HANDLE))) return 0;
    request = (DirectoryRequest){.body = malloc(MAX_DIRECTORY_BYTES)};
    if (!request.body) return 0;
    memcpy(request.url, url, (size_t)length);
    worker = threadCreate(fetch_directory, &request, 64 * 1024, priority, -2, false);
    if (!worker) {
        free(request.body);
        request.body = NULL;
    }
    return worker != NULL;
}

int32_t m3ds_community_http_poll(uint8_t *output, int32_t capacity) {
    if (!worker) return -1;
    if (R_FAILED(threadJoin(worker, 0))) return 0;
    threadFree(worker);
    worker = NULL;
    int32_t result = -1;
    if (request.success && output && capacity > 0 && (size_t)capacity >= request.used) {
        memcpy(output, request.body, request.used);
        result = (int32_t)request.used;
    }
    free(request.body);
    request.body = NULL;
    return result;
}
