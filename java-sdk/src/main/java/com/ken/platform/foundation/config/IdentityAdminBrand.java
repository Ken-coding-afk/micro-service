package com.ken.platform.foundation.config;

import java.net.URI;
import java.util.Map;

/** 身份服务专属的非敏感品牌快照；不接收 HTML、外部资源或可执行扩展。 */
public record IdentityAdminBrand(String projectDisplayName, String logoUrl) {
  public IdentityAdminBrand {
    if (projectDisplayName == null
        || logoUrl == null
        || projectDisplayName.codePointCount(0, projectDisplayName.length()) > 128
        || !projectDisplayName.isEmpty() && projectDisplayName.isBlank()
        || projectDisplayName
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code >= 0xD800 && code <= 0xDFFF)
        || projectDisplayName.contains("<")
        || projectDisplayName.contains(">")) throw invalid();
    if (!logoUrl.isEmpty()) {
      try {
        URI uri = URI.create(logoUrl);
        String path = uri.getPath();
        if (logoUrl.codePointCount(0, logoUrl.length()) > 1024
            || uri.isAbsolute()
            || uri.getRawAuthority() != null
            || uri.getRawQuery() != null
            || uri.getRawFragment() != null
            || path == null
            || !path.startsWith("/")
            || path.startsWith("//")
            || path.contains("\\")
            || path.codePoints()
                .anyMatch(code -> Character.isISOControl(code) || code >= 0xD800 && code <= 0xDFFF)
            || java.util.Arrays.stream(path.split("/", -1))
                .anyMatch(part -> part.equals(".") || part.equals(".."))) throw invalid();
      } catch (IllegalArgumentException malformed) {
        throw invalid();
      }
    }
  }

  public static IdentityAdminBrand defaults() {
    return new IdentityAdminBrand("", "");
  }

  static IdentityAdminBrand take(Map<String, Object> source, String service) {
    if (!"identity-service".equals(service)) return defaults();
    if (source.containsKey("project_display_name") && source.get("project_display_name") == null
        || source.containsKey("logo_url") && source.get("logo_url") == null) throw invalid();
    return new IdentityAdminBrand(
        text(source.remove("project_display_name")), text(source.remove("logo_url")));
  }

  private static String text(Object value) {
    if (value == null) return "";
    if (value instanceof String text) return text;
    throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("身份品牌配置必须为纯文本名称和同源绝对路径");
  }
}
