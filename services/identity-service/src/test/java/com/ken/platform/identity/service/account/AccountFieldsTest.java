package com.ken.platform.identity.service.account;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.http.PlatformException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AccountFieldsTest {
  @Test
  void usernameUsesRootCaseAndBoundaryWhitespaceOnly() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertThat(AccountFields.username("  I-Student　")).isEqualTo("i-student");
      assertThat(AccountFields.username("学生 A")).isEqualTo("学生 a");
    } finally {
      Locale.setDefault(previous);
    }
    assertThat(AccountFields.displayName("  显示名  ")).isEqualTo("  显示名  ");
  }

  @Test
  void codePointBoundsRejectControlMarkupAndMalformedUnicode() {
    assertThat(AccountFields.username("🚀".repeat(128))).isEqualTo("🚀".repeat(128));
    for (String invalid : List.of("　 ", "a\n", "<script>", "x".repeat(129), "x\uD800")) {
      assertThatThrownBy(() -> AccountFields.username(invalid))
          .isInstanceOf(PlatformException.class);
      assertThatThrownBy(() -> AccountFields.displayName(invalid))
          .isInstanceOf(PlatformException.class);
    }
  }
}
