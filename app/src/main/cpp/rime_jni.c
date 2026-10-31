/*
 * librime <-> JNI bridge for AgentIme.
 *
 * The native engine is real librime 1.17 (see jniLibs/<abi>/librime.so): this
 * file only marshals Kotlin calls into the stable C API declared by
 * rime_api.h and keeps a single RimeSessionId guarded by a mutex.
 *
 * Threading: Rime is not thread-safe per session, so every call that touches
 * g_session is serialized by g_lock. Queries are expected to be short.
 */

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "rime_api.h"
#include "t9_syllables.h"

#define TAG "AgentImeRime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAX_CANDIDATES 20
#define MAX_T9_RESULTS 12
#define MAX_STR 96
#define MAX_T9_INPUT 8

static RimeApi *g_api = NULL;
static RimeSessionId g_session = 0;
static volatile int g_ready = 0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* ---------- essay.txt word-frequency table (T9 ranking) ----------
 * essay.txt ships in assets/rime and is copied to shared_data_dir at run time.
 * Loading it lets ambiguous digit strings prefer "mi hao" (你好) over "mi gan"
 * (蜜柑) instead of ranking purely by syllable count.
 */
#define ESSAY_SLOTS (1u << 19)

typedef struct {
    char *key;
    unsigned long long freq;
} EssayEntry;

static EssayEntry *g_essay = NULL;

static unsigned int essay_hash(const char *s) {
    unsigned int h = 2166136261u;
    while (*s) {
        h ^= (unsigned char) *s++;
        h *= 16777619u;
    }
    return h;
}

static void essay_insert(char *key, unsigned long long freq) {
    unsigned int mask = ESSAY_SLOTS - 1;
    unsigned int i = essay_hash(key) & mask;
    for (int probe = 0; probe < 32; probe++) {
        EssayEntry *e = &g_essay[i];
        if (!e->key) {
            e->key = key;
            e->freq = freq;
            return;
        }
        if (strcmp(e->key, key) == 0) {
            if (freq > e->freq) e->freq = freq;
            free(key);
            return;
        }
        i = (i + 1) & mask;
    }
    free(key);
}

static unsigned long long essay_get(const char *word) {
    if (!g_essay || !word || !*word) return 0;
    unsigned int mask = ESSAY_SLOTS - 1;
    unsigned int i = essay_hash(word) & mask;
    for (int probe = 0; probe < 32; probe++) {
        EssayEntry *e = &g_essay[i];
        if (!e->key) return 0;
        if (strcmp(e->key, word) == 0) return e->freq;
        i = (i + 1) & mask;
    }
    return 0;
}

static void essay_load(const char *shared_dir) {
    if (g_essay || !shared_dir) return;
    char path[512];
    snprintf(path, sizeof(path), "%s/essay.txt", shared_dir);
    FILE *fp = fopen(path, "rb");
    if (!fp) {
        LOGI("essay.txt not found at %s; T9 ranking falls back to heuristics", path);
        return;
    }
    g_essay = calloc(ESSAY_SLOTS, sizeof(EssayEntry));
    if (!g_essay) {
        fclose(fp);
        return;
    }
    char line[512];
    int loaded = 0;
    while (fgets(line, sizeof(line), fp)) {
        char *tab = strchr(line, '\t');
        if (!tab) continue;
        *tab = '\0';
        unsigned long long f = strtoull(tab + 1, NULL, 10);
        char *key = strdup(line);
        if (!key) continue;
        essay_insert(key, f);
        loaded++;
    }
    fclose(fp);
    LOGI("essay.txt loaded: %d entries from %s", loaded, path);
}

static RimeApi *api_get(void) {
    if (!g_api) {
        g_api = rime_get_api();
    }
    return g_api;
}

/* ---------- small JNI helpers ---------- */

static jobject new_arraylist(JNIEnv *env) {
    jclass cls = (*env)->FindClass(env, "java/util/ArrayList");
    if (!cls) return NULL;
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "()V");
    jobject list = NULL;
    if (ctor) list = (*env)->NewObject(env, cls, ctor);
    (*env)->DeleteLocalRef(env, cls);
    return list;
}

static void list_add(JNIEnv *env, jobject list, const char *text) {
    if (!list || !text) return;
    jclass cls = (*env)->GetObjectClass(env, list);
    jmethodID add = (*env)->GetMethodID(env, cls, "add", "(Ljava/lang/Object;)Z");
    if (add) {
        jstring js = (*env)->NewStringUTF(env, text);
        if (js) {
            (*env)->CallBooleanMethod(env, list, add, js);
            (*env)->DeleteLocalRef(env, js);
        }
    }
    (*env)->DeleteLocalRef(env, cls);
}

/* ---------- T9 digit table ---------- */

static char g_t9_digits[kT9SyllableCount][8];
static int g_t9_digits_ready = 0;

static char t9_digit_of(char c) {
    switch (c) {
        case 'a': case 'b': case 'c': return '2';
        case 'd': case 'e': case 'f': return '3';
        case 'g': case 'h': case 'i': return '4';
        case 'j': case 'k': case 'l': return '5';
        case 'm': case 'n': case 'o': return '6';
        case 'p': case 'q': case 'r': case 's': return '7';
        case 't': case 'u': case 'v': return '8';
        case 'w': case 'x': case 'y': case 'z': return '9';
        default: return '0';
    }
}

static void build_t9_digits(void) {
    if (g_t9_digits_ready) return;
    for (int i = 0; i < kT9SyllableCount; i++) {
        const char *s = kT9Syllables[i];
        int n = 0;
        for (; s[n] && n < 7; n++) g_t9_digits[i][n] = t9_digit_of(s[n]);
        g_t9_digits[i][n] = '\0';
    }
    g_t9_digits_ready = 1;
}

/* ---------- librime queries (caller holds g_lock) ---------- */

static int query_candidates(const char *input, char out[][MAX_STR], int max) {
    if (!g_ready || !g_session) return 0;
    RimeApi *a = api_get();
    if (!a || !a->set_input || !a->get_context) return 0;

    a->clear_composition(g_session);
    if (!a->set_input(g_session, input)) return 0;

    RimeContext ctx;
    memset(&ctx, 0, sizeof(ctx));
    RIME_STRUCT_INIT(RimeContext, ctx);

    int n = 0;
    if (a->get_context(g_session, &ctx)) {
        int count = ctx.menu.num_candidates;
        for (int i = 0; i < count && n < max; i++) {
            const char *text = ctx.menu.candidates ? ctx.menu.candidates[i].text : NULL;
            if (text && *text) {
                snprintf(out[n], MAX_STR, "%s", text);
                n++;
            }
        }
        a->free_context(&ctx);
    }
    a->clear_composition(g_session);
    return n;
}

/* ---------- candidate ordering (pinyin) ---------- */

/* Number of UTF-8 code points in a NUL-terminated string. */
static int utf8_len(const char *s) {
    int n = 0;
    for (const unsigned char *p = (const unsigned char *) s; *p; p++) {
        if ((*p & 0xC0) != 0x80) n++;
    }
    return n;
}

/* Fewest syllables that exactly cover `pinyin` with the bundled syllable
   table; 0 when the string cannot be segmented at all. */
static int min_syllables(const char *pinyin) {
    size_t len = strlen(pinyin);
    if (len == 0 || len > 48) return 0;
    int dp[49];
    for (size_t i = 0; i <= len; i++) dp[i] = 1 << 20;
    dp[0] = 0;
    for (size_t i = 0; i < len; i++) {
        if (dp[i] >= (1 << 20)) continue;
        for (int k = 0; k < kT9SyllableCount; k++) {
            const char *syl = kT9Syllables[k];
            size_t sl = strlen(syl);
            if (sl == 0 || i + sl > len) continue;
            if (memcmp(pinyin + i, syl, sl) != 0) continue;
            if (dp[i + sl] > dp[i] + 1) dp[i + sl] = dp[i] + 1;
        }
    }
    return (dp[len] >= (1 << 20)) ? 0 : dp[len];
}

/* Stable re-ordering: candidates that cover the whole input (at least as many
   characters as syllables) come first, ordered by essay frequency; partial
   matches keep librime's order at the tail. Makes "nihao" -> 你好 deterministic
   instead of depending on the user dictionary's learning history. */
static void rank_pinyin_candidates(const char *input, char out[][MAX_STR], int n) {
    if (n < 2 || !g_essay) return;
    /* librime may hand back spacings such as "ni hao"; strip the delimiters so
       the syllable count is computed on plain letters. */
    char compact[64];
    size_t c = 0;
    for (const char *p = input; *p && c < sizeof(compact) - 1; p++) {
        if (*p == ' ' || *p == '\'') continue;
        compact[c++] = *p;
    }
    compact[c] = '\0';
    int syl = min_syllables(compact);
    if (syl <= 0) return;

    unsigned long long key[MAX_CANDIDATES];
    for (int i = 0; i < n; i++) {
        int chars = utf8_len(out[i]);
        unsigned long long tier = (chars >= syl) ? 1ull : 0ull;
        unsigned long long freq = essay_get(out[i]);
        if (freq > (1ull << 40) - 1) freq = (1ull << 40) - 1;
        key[i] = (tier << 40) | freq;
    }
    for (int i = 1; i < n; i++) {
        char tmp[MAX_STR];
        snprintf(tmp, sizeof(tmp), "%s", out[i]);
        unsigned long long k = key[i];
        int j = i - 1;
        while (j >= 0 && key[j] < k) {
            snprintf(out[j + 1], MAX_STR, "%s", out[j]);
            key[j + 1] = key[j];
            j--;
        }
        snprintf(out[j + 1], MAX_STR, "%s", tmp);
        key[j + 1] = k;
    }
    LOGI("pinyin ranking: '%s' min_syl=%d top='%s'", input, syl, out[0]);
}

typedef struct {
    char items[MAX_T9_RESULTS][MAX_STR];
    int parts[MAX_T9_RESULTS]; /* syllable count per segmentation, for ranking */
    int count;
} T9Results;

static void t9_walk(const char *digits, int pos, int len, char *buf, int *buflen, int depth, T9Results *res) {
    if (res->count >= MAX_T9_RESULTS) return;
    if (pos == len) {
        buf[*buflen] = '\0';
        for (int i = 0; i < res->count; i++) {
            if (strcmp(res->items[i], buf) == 0) return;
        }
        snprintf(res->items[res->count], MAX_STR, "%s", buf);
        res->parts[res->count] = depth;
        res->count++;
        return;
    }
    for (int i = 0; i < kT9SyllableCount; i++) {
        const char *d = g_t9_digits[i];
        int dl = (int) strlen(d);
        if (dl == 0 || pos + dl > len) continue;
        if (strncmp(digits + pos, d, (size_t) dl) != 0) continue;

        int saved = *buflen;
        const char *syl = kT9Syllables[i];
        int sl = (int) strlen(syl);
        if (saved > 0) {
            if (saved + 1 >= MAX_STR) continue;
            buf[(*buflen)++] = ' ';
        }
        if (*buflen + sl >= MAX_STR) { *buflen = saved; continue; }
        memcpy(buf + *buflen, syl, (size_t) sl);
        *buflen += sl;

        t9_walk(digits, pos + dl, len, buf, buflen, depth + 1, res);
        *buflen = saved;
        if (res->count >= MAX_T9_RESULTS) return;
    }
}

/* Prefer segmentations with fewer syllables (usually the intended reading),
 * then shorter spellings: "mi hao" should outrank "mi ga o". */
static void t9_sort(T9Results *res) {
    for (int i = 1; i < res->count; i++) {
        char key[MAX_STR];
        snprintf(key, MAX_STR, "%s", res->items[i]);
        int kp = res->parts[i];
        int klen = (int) strlen(key);
        int j = i - 1;
        while (j >= 0 && (res->parts[j] > kp ||
                          (res->parts[j] == kp && (int) strlen(res->items[j]) > klen))) {
            snprintf(res->items[j + 1], MAX_STR, "%s", res->items[j]);
            res->parts[j + 1] = res->parts[j];
            j--;
        }
        snprintf(res->items[j + 1], MAX_STR, "%s", key);
        res->parts[j + 1] = kp;
    }
}

/* Returns the word the IME would commit for this spelling: the first entry of
 * the same ranked list the 26-key candidate bar shows, so T9 scoring and
 * pinyin ranking can never disagree. */
static void query_top_commit(const char *input, char *out, int max) {
    out[0] = '\0';
    if (!g_ready || !g_session) return;
    char items[8][MAX_STR];
    int n = query_candidates(input, items, 8);
    if (n <= 0) return;
    rank_pinyin_candidates(input, items, n);
    snprintf(out, max, "%s", items[0]);
}

/* Ranks segmentations by the essay.txt frequency of the word librime picks for
 * them, so an ambiguous digit string prefers the common reading. */
static void t9_rank_by_frequency(T9Results *res) {
    if (!g_essay || res->count <= 1) return;
    unsigned long long score[MAX_T9_RESULTS];
    for (int i = 0; i < res->count; i++) {
        char word[MAX_STR];
        query_top_commit(res->items[i], word, MAX_STR);
        score[i] = essay_get(word);
    }
    for (int i = 1; i < res->count; i++) {
        char key[MAX_STR];
        snprintf(key, MAX_STR, "%s", res->items[i]);
        int kp = res->parts[i];
        unsigned long long ks = score[i];
        int j = i - 1;
        while (j >= 0 && score[j] < ks) {
            snprintf(res->items[j + 1], MAX_STR, "%s", res->items[j]);
            res->parts[j + 1] = res->parts[j];
            score[j + 1] = score[j];
            j--;
        }
        snprintf(res->items[j + 1], MAX_STR, "%s", key);
        res->parts[j + 1] = kp;
        score[j + 1] = ks;
    }
    LOGI("t9 ranking: '%s' score=%llu, '%s' score=%llu", res->items[0], score[0],
         res->count > 1 ? res->items[1] : "", res->count > 1 ? score[1] : 0ULL);
}

/* ---------- JNI entry points ---------- */

JNIEXPORT jboolean JNICALL
Java_app_agentime_RimeJniBridge_initialize(JNIEnv *env, jobject thiz, jstring shared_dir, jstring user_dir) {
    (void) thiz;
    pthread_mutex_lock(&g_lock);
    jboolean ok = JNI_FALSE;
    if (g_ready) {
        pthread_mutex_unlock(&g_lock);
        return JNI_TRUE;
    }
    const char *shared = (*env)->GetStringUTFChars(env, shared_dir, NULL);
    const char *user = (*env)->GetStringUTFChars(env, user_dir, NULL);
    if (!shared || !user) goto release;

    RimeApi *a = api_get();
    if (!a) {
        LOGE("rime_get_api() returned NULL (librime.so not linked?)");
        goto release;
    }

    RimeTraits traits;
    memset(&traits, 0, sizeof(traits));
    RIME_STRUCT_INIT(RimeTraits, traits);
    traits.shared_data_dir = shared;
    traits.user_data_dir = user;
    traits.distribution_name = "AgentIme";
    traits.distribution_code_name = "agent_ime";
    traits.distribution_version = "0.1.0";
    traits.app_name = "rime.agent_ime";
    traits.min_log_level = 0;
    traits.log_dir = ""; /* stderr only -> logcat */

    LOGI("rime setup shared=%s user=%s", shared, user);
    a->setup(&traits);
    essay_load(shared);
    a->initialize(&traits);

    if (a->start_maintenance) a->start_maintenance(True);
    if (a->join_maintenance_thread) a->join_maintenance_thread();

    g_session = a->create_session();
    if (!g_session) {
        LOGE("create_session failed");
        goto release;
    }
    if (a->select_schema) {
        Bool selected = a->select_schema(g_session, "luna_pinyin");
        LOGI("select_schema(luna_pinyin) = %d", (int) selected);
        if (!selected) {
            g_session = 0;
            goto release;
        }
    }
    if (a->set_option) {
        a->set_option(g_session, "ascii_mode", False);
        a->set_option(g_session, "full_shape", False);
    }
    g_ready = 1;
    ok = JNI_TRUE;
    LOGI("librime ready, version=%s", a->get_version ? a->get_version() : "?");

release:
    if (shared) (*env)->ReleaseStringUTFChars(env, shared_dir, shared);
    if (user) (*env)->ReleaseStringUTFChars(env, user_dir, user);
    pthread_mutex_unlock(&g_lock);
    return ok;
}

JNIEXPORT jboolean JNICALL
Java_app_agentime_RimeJniBridge_isReady(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return g_ready ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_app_agentime_RimeJniBridge_rimeVersion(JNIEnv *env, jobject thiz) {
    (void) thiz;
    RimeApi *a = api_get();
    const char *v = (a && a->get_version) ? a->get_version() : NULL;
    return v ? (*env)->NewStringUTF(env, v) : NULL;
}

JNIEXPORT jobject JNICALL
Java_app_agentime_RimeJniBridge_pinyinCandidates(JNIEnv *env, jobject thiz, jstring composing) {
    (void) thiz;
    jobject list = new_arraylist(env);
    if (!composing) return list;

    const char *input = (*env)->GetStringUTFChars(env, composing, NULL);
    if (!input) return list;
    if (*input) {
        pthread_mutex_lock(&g_lock);
        if (g_ready) {
            char items[MAX_CANDIDATES][MAX_STR];
            int n = query_candidates(input, items, MAX_CANDIDATES);
            rank_pinyin_candidates(input, items, n);
            LOGI("pinyin '%s' -> %d candidates%s%s", input, n,
                 n > 0 ? ", first=" : "", n > 0 ? items[0] : "");
            for (int i = 0; i < n; i++) list_add(env, list, items[i]);
        }
        pthread_mutex_unlock(&g_lock);
    }
    (*env)->ReleaseStringUTFChars(env, composing, input);
    return list;
}

JNIEXPORT jobject JNICALL
Java_app_agentime_RimeJniBridge_t9Candidates(JNIEnv *env, jobject thiz, jstring digits) {
    (void) thiz;
    jobject list = new_arraylist(env);
    if (!digits) return list;

    const char *raw = (*env)->GetStringUTFChars(env, digits, NULL);
    if (!raw) return list;

    char input[MAX_T9_INPUT + 1];
    int len = 0;
    for (const char *p = raw; *p && len < MAX_T9_INPUT; p++) {
        if (*p >= '0' && *p <= '9') input[len++] = *p;
    }
    input[len] = '\0';

    if (len > 0) {
        pthread_mutex_lock(&g_lock);
        if (g_ready) {
            build_t9_digits();
            T9Results *res = calloc(1, sizeof(T9Results));
            if (res) {
                char buf[MAX_STR];
                int buflen = 0;
                t9_walk(input, 0, len, buf, &buflen, 0, res);
                t9_sort(res);
                t9_rank_by_frequency(res);
                LOGI("t9 '%s' -> %d segmentations, first='%s'", input, res->count,
                     res->count > 0 ? res->items[0] : "");

                char seen[MAX_CANDIDATES][MAX_STR];
                int nseen = 0;
                for (int i = 0; i < res->count && nseen < MAX_CANDIDATES; i++) {
                    char items[MAX_CANDIDATES][MAX_STR];
                    int n = query_candidates(res->items[i], items, MAX_CANDIDATES);
                    rank_pinyin_candidates(res->items[i], items, n);
                    for (int j = 0; j < n && nseen < MAX_CANDIDATES; j++) {
                        int dup = 0;
                        for (int k = 0; k < nseen; k++) {
                            if (strcmp(seen[k], items[j]) == 0) { dup = 1; break; }
                        }
                        if (dup) continue;
                        snprintf(seen[nseen], MAX_STR, "%s", items[j]);
                        list_add(env, list, items[j]);
                        nseen++;
                    }
                }
                free(res);
            }
        }
        pthread_mutex_unlock(&g_lock);
    }

    (*env)->ReleaseStringUTFChars(env, digits, raw);
    return list;
}

JNIEXPORT jstring JNICALL
Java_app_agentime_RimeJniBridge_commitPinyin(JNIEnv *env, jobject thiz, jstring composing) {
    (void) thiz;
    jstring result = NULL;
    if (!composing) return NULL;

    const char *input = (*env)->GetStringUTFChars(env, composing, NULL);
    if (!input) return NULL;

    pthread_mutex_lock(&g_lock);
    if (!g_ready || !*input) LOGI("commit '%s' skipped (ready=%d)", input, (int) g_ready);
    if (g_ready && *input) {
        RimeApi *a = api_get();
        if (a && a->set_input && a->select_candidate && a->get_commit) {
            a->clear_composition(g_session);
            if (a->set_input(g_session, input) && a->select_candidate(g_session, 0)) {
                RimeCommit commit;
                memset(&commit, 0, sizeof(commit));
                RIME_STRUCT_INIT(RimeCommit, commit);
                if (a->get_commit(g_session, &commit)) {
                    if (commit.text && *commit.text) {
                        result = (*env)->NewStringUTF(env, commit.text);
                    }
                    a->free_commit(&commit);
                }
            }
            a->clear_composition(g_session);
        }
    }
    pthread_mutex_unlock(&g_lock);

    LOGI("commit '%s' -> '%s'", input, result ? "ok" : "null");
    (*env)->ReleaseStringUTFChars(env, composing, input);
    return result;
}
