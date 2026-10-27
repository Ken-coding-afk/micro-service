package com.ken.platform.identity.service.permission;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.service.permission.PermissionCatalogService.Entry;
import com.ken.platform.identity.service.permission.PermissionCatalogService.PrincipalType;
import com.ken.platform.identity.service.permission.PermissionCatalogService.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 发布制品内的原始 UTF-8 permissions.yaml；校验和覆盖传入文本的全部字节。 */
public final class PermissionManifest {
  private static final int MAX_BYTES = 1_048_576;
  private static final Set<String> FIELDS =
      Set.of(
          "code",
          "name",
          "principal_type",
          "group_code",
          "group_name",
          "resource_code",
          "resource_name",
          "action_code",
          "description",
          "sort",
          "data_scopes",
          "oauth_scope",
          "status");
  private final String service;
  private final String releaseVersion;
  private final String checksum;
  private final List<Entry> entries;

  private PermissionManifest(
      String service, String releaseVersion, String checksum, List<Entry> entries) {
    this.service = service;
    this.releaseVersion = releaseVersion;
    this.checksum = checksum;
    this.entries = List.copyOf(entries);
  }

  public String service() {
    return service;
  }

  public String releaseVersion() {
    return releaseVersion;
  }

  public String checksum() {
    return checksum;
  }

  public List<Entry> entries() {
    return entries;
  }

  public static PermissionManifest parse(
      String service, String releaseVersion, String checksum, String yaml) {
    if (yaml == null
        || yaml.length() > MAX_BYTES
        || yaml.codePoints().anyMatch(value -> value >= 0xD800 && value <= 0xDFFF)
        || yaml.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
        || checksum == null
        || !checksum.matches("[0-9a-f]{64}")
        || !checksum.equals(sha256(yaml))) {
      throw invalid("清单超过大小上限或 SHA-256 校验不一致");
    }
    try {
      var options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setMaxAliasesForCollections(0);
      options.setNestingDepthLimit(20);
      options.setCodePointLimit(MAX_BYTES);
      var root =
          object(
              new Yaml(new SafeConstructor(options)).load(yaml), Set.of("service", "permissions"));
      if (service == null
          || !service.equals(text(root, "service", null))
          || !(root.get("permissions") instanceof List<?> raw)
          || raw.size() > 1000) {
        throw invalid("清单服务归属不一致或条目超过上限");
      }
      List<Entry> entries = new ArrayList<>();
      for (Object item : raw) {
        var row = object(item, FIELDS);
        if (!(row.get("data_scopes") instanceof List<?> values)) {
          throw invalid("data_scopes 必须是明确数组");
        }
        Set<DataScope> scopes = new HashSet<>();
        for (Object scope : values) {
          if (!(scope instanceof String value) || !scopes.add(DataScope.valueOf(value))) {
            throw invalid("范围非法或重复");
          }
        }
        Object sort = row.containsKey("sort") ? row.get("sort") : 0;
        if (!(sort instanceof Integer order)) {
          throw invalid("分组排序必须是整数");
        }
        entries.add(
            new Entry(
                text(row, "code", null),
                text(row, "name", null),
                PrincipalType.valueOf(text(row, "principal_type", "TENANT")),
                text(row, "group_code", null),
                text(row, "group_name", null),
                text(row, "resource_code", null),
                text(row, "resource_name", null),
                text(row, "action_code", null),
                text(row, "description", ""),
                order,
                Set.copyOf(scopes),
                text(row, "oauth_scope", null),
                Status.valueOf(text(row, "status", "ACTIVE"))));
      }
      return new PermissionManifest(service, releaseVersion, checksum, entries);
    } catch (PlatformException known) {
      throw known;
    } catch (RuntimeException malformed) {
      throw invalid("权限清单格式不合法");
    }
  }

  private static Map<?, ?> object(Object raw, Set<String> fields) {
    if (!(raw instanceof Map<?, ?> map)
        || map.keySet().stream()
            .anyMatch(key -> !(key instanceof String) || !fields.contains(key))) {
      throw invalid("清单含未知字段或非对象条目");
    }
    return map;
  }

  private static String text(Map<?, ?> row, String key, String fallback) {
    if (!row.containsKey(key)) {
      return fallback;
    }
    if (!(row.get(key) instanceof String value)) {
      throw invalid("清单文本字段类型不合法");
    }
    return value;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
    }
  }

  private static PlatformException invalid(String message) {
    return new PlatformException("PERMISSION_CATALOG_INVALID", message, 400);
  }
}
