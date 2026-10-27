package com.ken.platform.identity.service.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class PasswordCredentialsTest {
  private final SecurityPolicyMapper mapper = mock(SecurityPolicyMapper.class);
  private final PasswordCredentials passwords = new PasswordCredentials(mapper);
  private final SecurityPolicies.Effective policy =
      new SecurityPolicies.Effective(15, 10, 900, 900, 60, 60, 28800, 1800, 300, 0, null);

  @Test
  void bcryptPrefixCostAndWhitespaceArePreserved() {
    String clear = "  a thoughtfully long phrase  ";
    String hash = passwords.encode(clear, policy);
    assertThat(hash).startsWith("{bcrypt}$2a$12$");
    assertThat(passwords.matches(clear, hash)).isTrue();
    assertThat(passwords.matches(clear.strip(), hash)).isFalse();
    assertThat(passwords.matches(clear, hash.replace("$12$", "$10$"))).isFalse();
    assertThat(passwords.matches(clear, "{noop}" + clear)).isFalse();
    assertThat(passwords.matches(clear, "{bcrypt}broken")).isFalse();
  }

  @Test
  void standardEncoderAcceptsExistingBcryptCostsWithoutEnablingOtherAlgorithms() {
    String clear = "an existing account password";
    for (int cost : List.of(4, 10, 12)) {
      String stored =
          "{bcrypt}"
              + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(cost)
                  .encode(clear);
      assertThat(passwords.matches(clear, stored)).isTrue();
      assertThat(passwords.matches("wrong password", stored)).isFalse();
    }
    assertThat(passwords.matches(clear, "{noop}" + clear)).isFalse();
    assertThat(passwords.matches(clear, clear)).isFalse();
    assertThat(passwords.matches(clear, "{unknown}hash")).isFalse();
  }

  @Test
  void unicodeCodePointsAndUtf8BytesAreIndependentBoundaries() {
    for (String accepted :
        List.of("a valid sentence" + "x".repeat(48), "汉".repeat(24), "🚀".repeat(18))) {
      String hash = passwords.encode(accepted, policy);
      assertThat(passwords.matches(accepted, hash)).isTrue();
    }
    for (String rejected :
        List.of(
            "short-password",
            "x".repeat(65),
            "汉".repeat(24) + "a",
            "🚀".repeat(19),
            "a valid sentence\uD800")) {
      assertThatThrownBy(() -> passwords.encode(rejected, policy))
          .isInstanceOfSatisfying(
              PlatformException.class,
              error -> assertThat(error.code()).isEqualTo("PASSWORD_REJECTED"));
    }
    var tighter = new SecurityPolicies.Effective(32, 10, 900, 900, 60, 60, 28800, 1800, 300, 1, 1L);
    assertThatThrownBy(() -> passwords.encode("sufficient for default policy", tighter))
        .isInstanceOf(PlatformException.class);
  }

  @Test
  void frozenCommonListAndProjectAdditionsAreRejected() throws Exception {
    try (var input = getClass().getResourceAsStream("/security/passwords-common.txt")) {
      String weak =
          new String(input.readAllBytes(), StandardCharsets.UTF_8)
              .lines()
              .filter(
                  value ->
                      value.codePointCount(0, value.length()) >= 15
                          && value.length() <= 64
                          && value.getBytes(StandardCharsets.UTF_8).length <= 72)
              .findFirst()
              .orElseThrow();
      assertThatThrownBy(() -> passwords.encode(weak, policy))
          .isInstanceOf(PlatformException.class);
      assertThatThrownBy(() -> passwords.encode(weak.toUpperCase(java.util.Locale.ROOT), policy))
          .isInstanceOf(PlatformException.class);
    }
    String projectWeak = "project-specific unsafe phrase";
    String digest =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(projectWeak.getBytes(StandardCharsets.UTF_8)));
    when(mapper.additionallyBlocked(digest)).thenReturn(true);
    assertThatThrownBy(() -> passwords.encode(projectWeak, policy))
        .isInstanceOf(PlatformException.class);
    verify(mapper).additionallyBlocked(digest);
  }

  @Test
  void temporaryCredentialsAreRandomRedactedAndCanOnlyBeRevealedOnce() {
    var first = passwords.temporary(policy);
    var second = passwords.temporary(policy);
    String clear = first.secret().revealOnce();
    assertThat(clear).hasSize(32).isNotEqualTo(second.secret().revealOnce());
    assertThat(passwords.matches(clear, first.hash())).isTrue();
    assertThat(first.toString()).doesNotContain(clear, first.hash());
    assertThat(first.secret().toString()).isEqualTo("OneTimeSecret[REDACTED]");
    assertThatThrownBy(first.secret()::revealOnce).isInstanceOf(IllegalStateException.class);
    var unused = passwords.temporary(policy);
    unused.secret().close();
    assertThatThrownBy(unused.secret()::revealOnce).isInstanceOf(IllegalStateException.class);
  }
}
