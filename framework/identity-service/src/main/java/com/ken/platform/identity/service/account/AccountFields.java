package com.ken.platform.identity.service.account;

import com.ken.platform.foundation.http.PlatformException;
import java.util.Locale;

public final class AccountFields {
  private AccountFields() {}

  public static String username(String value) {
    if (value == null || value.codePoints().anyMatch(Character::isISOControl)) {
      throw invalid();
    }
    String normalized = value.strip().toLowerCase(Locale.ROOT);
    plain(normalized);
    return normalized;
  }

  public static String displayName(String value) {
    plain(value);
    return value;
  }

  private static void plain(String value) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > 128
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code >= 0xD800 && code <= 0xDFFF)
        || value.contains("<")
        || value.contains(">")) {
      throw invalid();
    }
  }

  private static PlatformException invalid() {
    return new PlatformException("ACCOUNT_FIELDS_INVALID", "账号名称不合法", 400);
  }
}
