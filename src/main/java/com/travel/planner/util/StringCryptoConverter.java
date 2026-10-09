package com.travel.planner.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 개인정보 컬럼 암호화.
 *
 * 새로 저장하는 값은 AES-256-GCM(매번 다른 IV)으로 암호화하고 "v2:" 접두어를 붙인다.
 * 이전 방식(AES/ECB, JWT 시크릿 앞 16바이트를 그대로 키로 사용)은
 *  - 같은 값이 항상 같은 암호문이 되어 "여성"/"남성" 같은 값의 분포가 그대로 드러나고,
 *  - JWT 서명 키와 같은 키를 써서 하나가 새면 둘 다 뚫렸다.
 * 예전에 저장된 값은 읽을 때 구형 방식으로 복호화하므로 별도 마이그레이션 없이 그대로 동작하고,
 * 다음에 그 행이 저장될 때 새 방식으로 바뀐다.
 *
 * 키 설정 (application-secret.yml 권장)
 *  - app.crypto.secret        : 새로 암호화할 때 쓰는 키. 비워 두면 jwt.secret 에서 유도한 키를 쓴다.
 *                               나중에 추가해도 그 전에 저장된 값은 계속 읽힌다.
 *  - app.crypto.legacy-secret : jwt.secret 을 바꿀 때만 필요. "바꾸기 전의 jwt.secret" 을 넣어 두면
 *                               그 키로 저장된 기존 값(구형 ECB 포함)을 계속 읽을 수 있다.
 */
@Converter
@Component
public class StringCryptoConverter implements AttributeConverter<String, String> {

    private static final String PREFIX = "v2:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String KEY_CONTEXT = "planner-db-field-v2:";

    private static String jwtSecret = "DefaultSecret123";   // 안전장치용 기본값 (설정이 주입되기 전)
    private static String cryptoSecret = "";
    private static String legacySecret = "";

    /** 새로 암호화할 때 쓰는 키 */
    private static byte[] key;
    /** 예전 jwt.secret 에서 유도한 GCM 키: app.crypto.secret 을 나중에 추가했거나 jwt.secret 을 바꾼 경우의 기존 값 복호화용 */
    private static byte[] previousKey;
    /** 구형(ECB) 데이터 복호화용 키: 예전 구현과 동일하게 jwt.secret 앞 16바이트 */
    private static byte[] legacyKey;

    static {
        rebuildKeys();
    }

    // 하이버네이트가 컨버터를 직접 생성하는 경우에도 같은 키를 쓰도록 static 에 주입합니다.
    @Value("${jwt.secret}")
    public void setJwtSecret(String secret) {
        jwtSecret = secret;
        rebuildKeys();
    }

    @Value("${app.crypto.secret:}")
    public void setCryptoSecret(String secret) {
        cryptoSecret = secret == null ? "" : secret;
        rebuildKeys();
    }

    @Value("${app.crypto.legacy-secret:}")
    public void setLegacySecret(String secret) {
        legacySecret = secret == null ? "" : secret;
        rebuildKeys();
    }

    private static void rebuildKeys() {
        String oldJwtSecret = legacySecret.isEmpty() ? jwtSecret : legacySecret;
        legacyKey = Arrays.copyOf(oldJwtSecret.getBytes(StandardCharsets.UTF_8), 16);
        previousKey = sha256(KEY_CONTEXT + oldJwtSecret);
        key = cryptoSecret.isEmpty() ? sha256(KEY_CONTEXT + jwtSecret) : sha256(cryptoSecret);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("DB 암호화 실패", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;

        if (dbData.startsWith(PREFIX)) {
            byte[] all;
            try {
                all = Base64.getDecoder().decode(dbData.substring(PREFIX.length()));
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("DB 복호화 실패 (저장된 값의 형식이 올바르지 않습니다)", e);
            }
            // 현재 키로 먼저 풀고, 안 되면 이전 키(설정을 바꾸기 전에 저장된 값)로 시도한다.
            for (byte[] candidate : new byte[][]{key, previousKey}) {
                try {
                    byte[] iv = Arrays.copyOfRange(all, 0, IV_LENGTH);
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(candidate, "AES"), new GCMParameterSpec(TAG_BITS, iv));
                    return new String(cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    // 이 키로 암호화한 값이 아님 → 다음 키
                }
            }
            throw new RuntimeException("DB 복호화 실패: jwt.secret 을 바꿨다면 app.crypto.legacy-secret 에 이전 값을 넣어 주세요.");
        }

        // 구형 데이터 (AES/ECB)
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(legacyKey, "AES"));
            return new String(cipher.doFinal(Base64.getDecoder().decode(dbData)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return dbData; // 이미 평문인 구형 데이터 호환용
        }
    }
}
