#ifndef MOCKSEC_ED25519_VERIFY_H
#define MOCKSEC_ED25519_VERIFY_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 纯 C 无依赖 Ed25519 验签（TweetNaCl 实现）
 * @return 0 表示签名合法；非 0 表示非法签名
 */
int ed25519_verify_sig(
    const uint8_t sig[64],
    const uint8_t *msg,
    size_t msg_len,
    const uint8_t pk[32]
);

#ifdef __cplusplus
}
#endif

#endif
