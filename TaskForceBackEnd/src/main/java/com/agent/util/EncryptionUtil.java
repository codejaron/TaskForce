package com.agent.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * AES 加密工具类
 * 用于加密/解密敏感信息(如 API Key)
 */
@Slf4j
@Component
public class EncryptionUtil {
    
    private static final String ALGORITHM = "AES";
    
    @Value("${app.security.encryption-key:MySecretKey12345}")
    private String encryptionKey;
    
    /**
     * 加密
     */
    public String encrypt(String data) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        
        try {
            SecretKeySpec keySpec = new SecretKeySpec(getPaddedKey(), ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(data.getBytes());
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new RuntimeException("Failed to encrypt data", e);
        }
    }
    
    /**
     * 解密
     */
    public String decrypt(String encryptedData) {
        if (encryptedData == null || encryptedData.isEmpty()) {
            return encryptedData;
        }
        
        try {
            SecretKeySpec keySpec = new SecretKeySpec(getPaddedKey(), ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decoded = Base64.getDecoder().decode(encryptedData);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted);
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("Failed to decrypt data", e);
        }
    }
    
    /**
     * 获取填充后的密钥(AES-128需要16字节)
     */
    private byte[] getPaddedKey() {
        byte[] key = encryptionKey.getBytes();
        byte[] paddedKey = new byte[16];
        System.arraycopy(key, 0, paddedKey, 0, Math.min(key.length, 16));
        return paddedKey;
    }
}
