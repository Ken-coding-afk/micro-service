package com.ken.platform.identity.service.security;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public final class PasswordCredentials {
  private static final String LIST_SHA256 =
      "1472aafa2561df5e3293aee252aee3ca660c12b399a283cf808bb01b39be388b";
  private static final Set<String> COMMON = common();
  private final PasswordEncoder encoder =
      new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
  private final SecureRandom random = new SecureRandom();
  private final SecurityPolicyMapper mapper;

  public PasswordCredentials(SecurityPolicyMapper mapper) {
    this.mapper = mapper;
  }

  public PasswordEncoder encoder() {
    return encoder;
  }

  public static final class OneTimeSecret implements AutoCloseable {
    private final char[] value;
    private boolean consumed;

    private OneTimeSecret(String value) {
      this.value = value.toCharArray();
    }

    public synchronized String revealOnce() {
      if (consumed) {
        throw new IllegalStateException("初始凭证不能重复交付");
      }
      String result = new String(value);
      close();
      return result;
    }

    @Override
    public synchronized void close() {
      Arrays.fill(value, '\0');
      consumed = true;
    }

    @Override
    public String toString() {
      return "OneTimeSecret[REDACTED]";
    }
  }

  public record Prepared(String hash, OneTimeSecret secret) {
    @Override
    public String toString() {
      return "Prepared[REDACTED]";
    }
  }

  public Prepared temporary(SecurityPolicies.Effective policy) {
    for (int attempt = 0; attempt < 10; attempt++) {
      byte[] bytes = new byte[24];
      random.nextBytes(bytes);
      String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      Arrays.fill(bytes, (byte) 0);
      if (blocked(value)) {
        continue;
      }
      return new Prepared(encode(value, policy), new OneTimeSecret(value));
    }
    throw new PlatformException("CREDENTIAL_GENERATION_UNAVAILABLE", "暂时无法生成初始凭证", 503);
  }

  public Prepared clientSecret() {
    byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Arrays.fill(bytes, (byte) 0);
    return new Prepared(encoder.encode(value), new OneTimeSecret(value));
  }

  public String encode(String value, SecurityPolicies.Effective policy) {
    if (!bounded(value)
        || policy == null
        || value.codePointCount(0, value.length()) < policy.passwordMinChars()
        || blocked(value)) {
      throw new PlatformException("PASSWORD_REJECTED", "密码长度、字节上限或弱密码检查未通过", 400);
    }
    return encoder.encode(value);
  }

  public boolean matches(String value, String hash) {
    if (!bounded(value) || hash == null) {
      return false;
    }
    try {
      return encoder.matches(value, hash);
    } catch (IllegalArgumentException unsupported) {
      // 未批准算法或损坏的存储格式均失败关闭，不退回明文匹配。
      return false;
    }
  }

  private boolean blocked(String value) {
    String lower = value.toLowerCase(Locale.ROOT);
    return COMMON.contains(lower)
        || mapper.additionallyBlocked(sha256(lower.getBytes(StandardCharsets.UTF_8)));
  }

  public static boolean bounded(String value) {
    return value != null
        && value.length() <= 128
        && value.codePointCount(0, value.length()) <= 64
        && value.codePoints().noneMatch(code -> code >= 0xD800 && code <= 0xDFFF)
        && value.getBytes(StandardCharsets.UTF_8).length <= 72;
  }

  private static Set<String> common() {
    try (var input =
        PasswordCredentials.class.getResourceAsStream("/security/passwords-common.txt")) {
      if (input == null) {
        throw new IllegalStateException("缺少发行密码名单");
      }
      byte[] bytes = input.readAllBytes();
      if (!sha256(bytes).equals(LIST_SHA256)) {
        throw new IllegalStateException("发行密码名单摘要不一致");
      }
      return new String(bytes, StandardCharsets.UTF_8)
          .lines()
          .map(value -> value.toLowerCase(Locale.ROOT))
          .collect(Collectors.toUnmodifiableSet());
    } catch (IOException failure) {
      throw new IllegalStateException("无法读取发行密码名单", failure);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
    }
  }
}
