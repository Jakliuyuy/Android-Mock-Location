#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "ed25519_verify.h"

namespace {

// 混淆常量（禁止明文落地 .rodata）
static const uint8_t ENC_URL[] = {0x32, 0x2e, 0x2e, 0x2a, 0x60, 0x75, 0x75, 0x6b, 0x68, 0x63, 0x74, 0x6b, 0x6f, 0x6b, 0x74, 0x63, 0x74, 0x6f, 0x6f, 0x75, 0x37, 0x36, 0x3b, 0x2a, 0x33, 0x75, 0x2c, 0x6b, 0x75, 0x32, 0x3f, 0x3b, 0x28, 0x2e, 0x38, 0x3f, 0x3b, 0x2e};
static const uint8_t ENC_PKG[] = {0x5f, 0x53, 0x51, 0x12, 0x51, 0x53, 0x5f, 0x57, 0x50, 0x53, 0x5f};
static const uint8_t ENC_PUBKEY[] = {0xc8, 0xfb, 0x56, 0x71, 0x59, 0x6b, 0xb2, 0xd5, 0xa9, 0x59, 0x65, 0xd2, 0xa5, 0x2d, 0xaa, 0x93, 0x44, 0xc5, 0x64, 0x2e, 0x26, 0x80, 0x56, 0x73, 0x13, 0x02, 0x1b, 0x5e, 0x57, 0xf2, 0x15, 0x89};
static const uint8_t ENC_SIG_REL[] = {0x42, 0x42, 0x16, 0x46, 0x43, 0x45, 0x4b, 0x4a, 0x43, 0x43, 0x43, 0x15, 0x42, 0x40, 0x45, 0x10, 0x41, 0x11, 0x40, 0x17, 0x44, 0x4a, 0x10, 0x10, 0x11, 0x16, 0x10, 0x17, 0x42, 0x17, 0x45, 0x16, 0x4b, 0x43, 0x43, 0x47, 0x17, 0x41, 0x4b, 0x42, 0x17, 0x17, 0x4b, 0x15, 0x4b, 0x17, 0x4b, 0x15, 0x41, 0x45, 0x10, 0x47, 0x11, 0x43, 0x17, 0x4a, 0x41, 0x10, 0x12, 0x40, 0x47, 0x42, 0x11, 0x40};
static const uint8_t ENC_SIG_DBG[] = {0x1f, 0x1c, 0x4f, 0x1e, 0x1b, 0x19, 0x1c, 0x4d, 0x10, 0x10, 0x1a, 0x4c, 0x1a, 0x1a, 0x1b, 0x11, 0x10, 0x4f, 0x4c, 0x1d, 0x1f, 0x1c, 0x1d, 0x4c, 0x4d, 0x1f, 0x11, 0x48, 0x4a, 0x19, 0x4f, 0x1a, 0x1b, 0x18, 0x1e, 0x4a, 0x48, 0x4d, 0x1d, 0x10, 0x18, 0x10, 0x48, 0x1a, 0x19, 0x1f, 0x1e, 0x4a, 0x4a, 0x1a, 0x1f, 0x1a, 0x48, 0x1a, 0x4b, 0x4a, 0x10, 0x4b, 0x1b, 0x1d, 0x1c, 0x4a, 0x4c, 0x4a};

static volatile int64_t g_lease_deadline_ms = 0;
static volatile uint64_t g_session_token = 0;

static void xor_decode(const uint8_t* src, size_t len, uint8_t key, char* out) {
    for (size_t i = 0; i < len; ++i) {
        out[i] = static_cast<char>(src[i] ^ key);
    }
    out[len] = '\0';
}

static void xor_decode_bytes(const uint8_t* src, size_t len, uint8_t key, uint8_t* out) {
    for (size_t i = 0; i < len; ++i) {
        out[i] = src[i] ^ key;
    }
}

static int64_t monotonic_ms() {
    struct timespec ts{};
    if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0) {
        clock_gettime(CLOCK_MONOTONIC, &ts);
    }
    return static_cast<int64_t>(ts.tv_sec) * 1000LL + static_cast<int64_t>(ts.tv_nsec / 1000000LL);
}

static bool is_debugger_attached() {
    FILE* f = fopen("/proc/self/status", "r");
    if (!f) return false;
    char line[256];
    bool traced = false;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            int pid = atoi(line + 10);
            if (pid != 0) traced = true;
            break;
        }
    }
    fclose(f);
    return traced;
}

static int hex_val(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static bool hex_to_bytes(const char* hex, size_t hex_len, uint8_t* out, size_t out_len) {
    if (!hex || hex_len != out_len * 2) return false;
    for (size_t i = 0; i < out_len; ++i) {
        int hi = hex_val(hex[2 * i]);
        int lo = hex_val(hex[2 * i + 1]);
        if (hi < 0 || lo < 0) return false;
        out[i] = static_cast<uint8_t>((hi << 4) | lo);
    }
    return true;
}

static bool verify_package_and_signature(JNIEnv* env, jobject context) {
    if (!env || !context) return false;

    jclass ctxCls = env->GetObjectClass(context);
    if (!ctxCls) return false;

    jmethodID getPkgNameMid = env->GetMethodID(ctxCls, "getPackageName", "()Ljava/lang/String;");
    jmethodID getPmMid = env->GetMethodID(ctxCls, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    if (!getPkgNameMid || !getPmMid) {
        env->DeleteLocalRef(ctxCls);
        return false;
    }

    auto jPkg = reinterpret_cast<jstring>(env->CallObjectMethod(context, getPkgNameMid));
    if (env->ExceptionCheck() || !jPkg) {
        env->ExceptionClear();
        env->DeleteLocalRef(ctxCls);
        return false;
    }

    char expectedPkg[sizeof(ENC_PKG) + 1];
    xor_decode(ENC_PKG, sizeof(ENC_PKG), 0x3C, expectedPkg);
    const char* actualPkg = env->GetStringUTFChars(jPkg, nullptr);
    bool pkgMatch = actualPkg && strcmp(actualPkg, expectedPkg) == 0;
    if (actualPkg) env->ReleaseStringUTFChars(jPkg, actualPkg);
    memset(expectedPkg, 0, sizeof(expectedPkg));
    if (!pkgMatch) {
        env->DeleteLocalRef(jPkg);
        env->DeleteLocalRef(ctxCls);
        return false;
    }

    jobject pm = env->CallObjectMethod(context, getPmMid);
    env->DeleteLocalRef(ctxCls);
    if (env->ExceptionCheck() || !pm) {
        env->ExceptionClear();
        env->DeleteLocalRef(jPkg);
        return false;
    }

    jclass pmCls = env->GetObjectClass(pm);
    jmethodID getPkgInfoMid = env->GetMethodID(
        pmCls,
        "getPackageInfo",
        "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;"
    );
    env->DeleteLocalRef(pmCls);
    if (!getPkgInfoMid) {
        env->DeleteLocalRef(pm);
        env->DeleteLocalRef(jPkg);
        return false;
    }

    // Build.VERSION.SDK_INT >= 28: GET_SIGNING_CERTIFICATES = 0x08000000
    jobject pkgInfo = env->CallObjectMethod(pm, getPkgInfoMid, jPkg, static_cast<jint>(0x08000000));
    if (env->ExceptionCheck() || !pkgInfo) {
        env->ExceptionClear();
        pkgInfo = env->CallObjectMethod(pm, getPkgInfoMid, jPkg, static_cast<jint>(64));
    }
    env->DeleteLocalRef(pm);
    env->DeleteLocalRef(jPkg);
    if (env->ExceptionCheck() || !pkgInfo) {
        env->ExceptionClear();
        return false;
    }

    jclass piCls = env->GetObjectClass(pkgInfo);
    jobjectArray sigArray = nullptr;

    jfieldID signingInfoFid = env->GetFieldID(piCls, "signingInfo", "Landroid/content/pm/SigningInfo;");
    if (!env->ExceptionCheck() && signingInfoFid) {
        jobject signingInfo = env->GetObjectField(pkgInfo, signingInfoFid);
        if (signingInfo) {
            jclass siCls = env->GetObjectClass(signingInfo);
            jmethodID getApksMid = env->GetMethodID(siCls, "getApkContentsSigners", "()[Landroid/content/pm/Signature;");
            if (getApksMid) {
                sigArray = reinterpret_cast<jobjectArray>(env->CallObjectMethod(signingInfo, getApksMid));
            }
            env->DeleteLocalRef(siCls);
            env->DeleteLocalRef(signingInfo);
        }
    } else {
        env->ExceptionClear();
    }

    if (!sigArray) {
        jfieldID sigsFid = env->GetFieldID(piCls, "signatures", "[Landroid/content/pm/Signature;");
        if (!env->ExceptionCheck() && sigsFid) {
            sigArray = reinterpret_cast<jobjectArray>(env->GetObjectField(pkgInfo, sigsFid));
        } else {
            env->ExceptionClear();
        }
    }
    env->DeleteLocalRef(piCls);
    env->DeleteLocalRef(pkgInfo);

    if (!sigArray || env->GetArrayLength(sigArray) <= 0) {
        if (sigArray) env->DeleteLocalRef(sigArray);
        return false;
    }

    jobject sigObj = env->GetObjectArrayElement(sigArray, 0);
    env->DeleteLocalRef(sigArray);
    if (!sigObj) return false;

    jclass sigCls = env->GetObjectClass(sigObj);
    jmethodID toByteArrayMid = env->GetMethodID(sigCls, "toByteArray", "()[B");
    env->DeleteLocalRef(sigCls);
    auto sigBytes = reinterpret_cast<jbyteArray>(env->CallObjectMethod(sigObj, toByteArrayMid));
    env->DeleteLocalRef(sigObj);
    if (env->ExceptionCheck() || !sigBytes) {
        env->ExceptionClear();
        return false;
    }

    // MessageDigest.getInstance("SHA-256").digest(sigBytes)
    jclass mdCls = env->FindClass("java/security/MessageDigest");
    jmethodID getInstMid = env->GetStaticMethodID(mdCls, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jstring shaName = env->NewStringUTF("SHA-256");
    jobject mdObj = env->CallStaticObjectMethod(mdCls, getInstMid, shaName);
    jmethodID digestMid = env->GetMethodID(mdCls, "digest", "([B)[B");
    auto digestArr = reinterpret_cast<jbyteArray>(env->CallObjectMethod(mdObj, digestMid, sigBytes));
    env->DeleteLocalRef(shaName);
    env->DeleteLocalRef(mdObj);
    env->DeleteLocalRef(mdCls);
    env->DeleteLocalRef(sigBytes);

    if (env->ExceptionCheck() || !digestArr || env->GetArrayLength(digestArr) != 32) {
        env->ExceptionClear();
        if (digestArr) env->DeleteLocalRef(digestArr);
        return false;
    }

    jbyte rawDigest[32];
    env->GetByteArrayRegion(digestArr, 0, 32, rawDigest);
    env->DeleteLocalRef(digestArr);

    char actualHex[65];
    static const char HEX[] = "0123456789abcdef";
    for (int i = 0; i < 32; ++i) {
        uint8_t b = static_cast<uint8_t>(rawDigest[i]);
        actualHex[2 * i] = HEX[b >> 4];
        actualHex[2 * i + 1] = HEX[b & 0x0f];
    }
    actualHex[64] = '\0';

    char relHex[65];
    char dbgHex[65];
    xor_decode(ENC_SIG_REL, sizeof(ENC_SIG_REL), 0x73, relHex);
    xor_decode(ENC_SIG_DBG, sizeof(ENC_SIG_DBG), 0x29, dbgHex);

    bool ok = (strcmp(actualHex, relHex) == 0) || (strcmp(actualHex, dbgHex) == 0);
    memset(relHex, 0, sizeof(relHex));
    memset(dbgHex, 0, sizeof(dbgHex));
    memset(actualHex, 0, sizeof(actualHex));
    return ok;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_mockloc_data_remote_NativeSecurityBridge_nativeGetEndpointUrl(
    JNIEnv* env,
    jclass /* clazz */,
    jobject context
) {
    if (is_debugger_attached() || !verify_package_and_signature(env, context)) {
        return env->NewStringUTF("http://127.0.0.1:0/invalid");
    }
    char url[sizeof(ENC_URL) + 1];
    xor_decode(ENC_URL, sizeof(ENC_URL), 0x5A, url);
    jstring res = env->NewStringUTF(url);
    memset(url, 0, sizeof(url));
    return res;
}

JNIEXPORT jlong JNICALL
Java_com_mockloc_data_remote_NativeSecurityBridge_nativeVerifyAndGrantLease(
    JNIEnv* env,
    jclass /* clazz */,
    jobject context,
    jstring jPayload,
    jstring jSigHex,
    jlong leaseSeconds,
    jboolean effectiveEnabled,
    jint minVersionCode,
    jint currentVersionCode
) {
    if (!jPayload || !jSigHex || is_debugger_attached() || !verify_package_and_signature(env, context)) {
        g_lease_deadline_ms = 0;
        g_session_token = 0;
        return 0L;
    }

    const char* payloadStr = env->GetStringUTFChars(jPayload, nullptr);
    jsize payloadLen = env->GetStringUTFLength(jPayload);
    const char* sigStr = env->GetStringUTFChars(jSigHex, nullptr);
    jsize sigLen = env->GetStringUTFLength(jSigHex);

    uint8_t sigBytes[64];
    uint8_t pubKey[32];
    xor_decode_bytes(ENC_PUBKEY, sizeof(ENC_PUBKEY), 0xA7, pubKey);

    bool sigValid = false;
    if (payloadStr && sigStr && hex_to_bytes(sigStr, static_cast<size_t>(sigLen), sigBytes, 64)) {
        sigValid = (ed25519_verify_sig(
            sigBytes,
            reinterpret_cast<const uint8_t*>(payloadStr),
            static_cast<size_t>(payloadLen),
            pubKey
        ) == 0);
    }

    memset(pubKey, 0, sizeof(pubKey));
    memset(sigBytes, 0, sizeof(sigBytes));
    if (payloadStr) env->ReleaseStringUTFChars(jPayload, payloadStr);
    if (sigStr) env->ReleaseStringUTFChars(jSigHex, sigStr);

    if (!sigValid || effectiveEnabled != JNI_TRUE || currentVersionCode < minVersionCode || leaseSeconds <= 0) {
        g_lease_deadline_ms = 0;
        g_session_token = 0;
        return 0L;
    }

    int64_t safeLeaseMs = leaseSeconds * 1000LL;
    if (safeLeaseMs > 86400000LL) safeLeaseMs = 86400000LL;
    int64_t nowMs = monotonic_ms();
    g_lease_deadline_ms = nowMs + safeLeaseMs;
    uint64_t token = (static_cast<uint64_t>(nowMs) ^ 0x9E3779B97F4A7C15ULL) | 1ULL;
    g_session_token = token;
    return static_cast<jlong>(token);
}

JNIEXPORT jlong JNICALL
Java_com_mockloc_data_remote_NativeSecurityBridge_nativeCheckLeaseToken(
    JNIEnv* env,
    jclass /* clazz */,
    jobject context
) {
    if (g_session_token == 0 || is_debugger_attached() || !verify_package_and_signature(env, context)) {
        g_lease_deadline_ms = 0;
        g_session_token = 0;
        return 0L;
    }
    if (monotonic_ms() > g_lease_deadline_ms) {
        g_lease_deadline_ms = 0;
        g_session_token = 0;
        return 0L;
    }
    return static_cast<jlong>(g_session_token);
}

JNIEXPORT void JNICALL
Java_com_mockloc_data_remote_NativeSecurityBridge_nativeRevokeLease(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
    g_lease_deadline_ms = 0;
    g_session_token = 0;
}

} // extern "C"
