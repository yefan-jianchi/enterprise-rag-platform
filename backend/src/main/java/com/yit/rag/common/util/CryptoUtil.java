package com.yit.rag.common.util;

import com.yit.rag.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API Key 加解密工具（AES-256-GCM）。
 *
 * <p>这个类解决什么问题：{@code ai_model_provider.api_key_cipher} 字段要求
 * 「禁止明文落库」。数据库被拖走、或者运维导出一份数据去排查问题，
 * 明文 Key 就等于直接泄露，还可能导致账单被人刷爆。
 *
 * <p>算法选择说明（面试会问「为什么不用 AES-CBC」）：
 * <ul>
 *   <li><b>GCM 而不是 CBC</b>：GCM 是 AEAD（带认证的加密），
 *       能同时保证机密性和完整性 —— 密文被篡改一个字节，解密会直接失败。
 *       CBC 只保证机密性，攻击者可以翻转密文比特来影响明文，需要额外加 HMAC 才算安全。</li>
 *   <li><b>每次加密用随机 IV</b>：GCM 下 IV 复用是灾难性的（会泄露明文异或关系并暴露认证密钥），
 *       所以每次加密都用 {@link SecureRandom} 生成 12 字节新 IV，
 *       并把 IV 拼在密文前面一起存。解密时先切出 IV 再解。</li>
 *   <li><b>128 位认证标签</b>：GCM 的标准强度，能有效防止伪造密文。</li>
 * </ul>
 *
 * <p>存储格式：{@code AES:<base64(iv || ciphertext||tag)>}。
 * 带前缀是为了将来换算法（比如换国密 SM4）时能做平滑迁移 ——
 * 解密时按前缀分派，新旧数据可以共存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoUtil {

    /** 密文前缀，标识算法版本 */
    private static final String PREFIX = "AES:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;      // GCM 推荐 96 bit
    private static final int TAG_LENGTH_BIT = 128;

    private final AppProperties props;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec keySpec;

    @PostConstruct
    void init() {
        String configured = props.getCrypto().getAesKey();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("app.crypto.aes-key 未配置，无法加解密 API Key");
        }
        byte[] keyBytes = normalizeKey(configured);
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
        log.info("API Key 加解密已就绪（AES-256-GCM，密钥长度 {} bit）", keyBytes.length * 8);
    }

    /**
     * 加密。返回 {@code AES:<base64>} 格式的字符串，可直接落库。
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            // iv || ciphertext，一起 base64
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密。空值直接返回空串；格式不对返回 null 并告警（不抛异常，
     * 因为「某个提供方 Key 坏了」不应该让整个请求挂掉，ModelRouter 会跳到下一个）。
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return "";
        }
        String payload = cipherText.startsWith(PREFIX) ? cipherText.substring(PREFIX.length()) : cipherText;
        try {
            byte[] combined = Base64.getDecoder().decode(payload);
            if (combined.length <= IV_LENGTH) {
                log.warn("密文长度异常，无法解密");
                return null;
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] cipherBytes = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, cipherBytes, 0, cipherBytes.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("API Key 解密失败（密钥可能已更换）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 脱敏展示：只保留头尾几位，用于管理端列表接口。
     * 管理端必须能看到「Key 配了没有」，但绝不能看到完整 Key。
     */
    public String mask(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return "";
        }
        if (plainKey.length() <= 8) {
            return "****";
        }
        return plainKey.substring(0, 4) + "****" + plainKey.substring(plainKey.length() - 4);
    }

    /** 计算 SHA-256（文件秒传去重也用得到） */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    /**
     * 把任意长度的配置密钥规范成 AES 合法长度（16/24/32 字节）。
     *
     * <p>这样运维就算把密钥写成一句中文口令，也不会在启动时崩掉，
     * 而是被确定性地派生成 256 位密钥。当然生产环境还是应该直接给 32 字节随机串。
     */
    private static byte[] normalizeKey(String configured) {
        byte[] raw = configured.getBytes(StandardCharsets.UTF_8);
        if (raw.length == 16 || raw.length == 24 || raw.length == 32) {
            return raw;
        }
        try {
            // 其他长度：取 SHA-256 得到固定 32 字节
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(raw);
        } catch (Exception e) {
            throw new IllegalStateException("AES 密钥规范化失败", e);
        }
    }
}
