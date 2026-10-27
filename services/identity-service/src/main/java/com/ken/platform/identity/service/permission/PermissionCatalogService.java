package com.ken.platform.identity.service.permission;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.security.DataScope;
import com.ken.platform.identity.dal.mapper.permission.PermissionCatalogMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 受限服务发布自己的 TENANT 目录；不写角色、成员或 OAuth 客户端。 */
@Service
public final class PermissionCatalogService {
  public enum PrincipalType {
    TENANT,
    PLATFORM
  }

  public enum Status {
    ACTIVE,
    DEPRECATED,
    DISABLED
  }

  public record Entry(
      String code,
      String name,
      PrincipalType principalType,
      String groupCode,
      String groupName,
      String resourceCode,
      String resourceName,
      String actionCode,
      String description,
      int sort,
      Set<DataScope> dataScopes,
      String oauthScope,
      Status status) {}

  public record Result(long catalogVersion, boolean changed, String digest) {}

  private record Group(String name, int sort) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ManagementAudits audits;
  private final PermissionCatalogMapper mapper;
  private final TransactionTemplate transactions;

  public PermissionCatalogService(
      PermissionCatalogMapper mapper, PlatformTransactionManager manager, ManagementAudits audits) {
    this.audits = audits;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(manager);
  }

  public Result publish(PermissionManifest manifest, long expectedVersion) {
    return audits.attempt(
        ManagementAudits.Action.CATALOG_PUBLISH,
        null,
        () -> publishManifest(manifest, expectedVersion));
  }

  public Result publish(
      PermissionManifest manifest, long expectedVersion, Runnable authorizationCheck) {
    return audits.attempt(
        ManagementAudits.Action.CATALOG_PUBLISH,
        null,
        () ->
            publish(
                manifest.service(),
                manifest.releaseVersion(),
                expectedVersion,
                manifest.entries(),
                manifest.checksum(),
                authorizationCheck));
  }

  private Result publishManifest(PermissionManifest manifest, long expectedVersion) {
    if (manifest == null) {
      throw invalid("缺少发布清单");
    }
    return publish(
        manifest.service(),
        manifest.releaseVersion(),
        expectedVersion,
        manifest.entries(),
        manifest.checksum());
  }

  Result publish(String owner, String releaseVersion, long expectedVersion, List<Entry> entries) {
    return audits.attempt(
        ManagementAudits.Action.CATALOG_PUBLISH,
        null,
        () -> publishEntries(owner, releaseVersion, expectedVersion, entries));
  }

  private Result publishEntries(
      String owner, String releaseVersion, long expectedVersion, List<Entry> entries) {
    return publish(owner, releaseVersion, expectedVersion, entries, null);
  }

  private Result publish(
      String owner,
      String releaseVersion,
      long expectedVersion,
      List<Entry> entries,
      String artifactChecksum) {
    return publish(owner, releaseVersion, expectedVersion, entries, artifactChecksum, () -> {});
  }

  private Result publish(
      String owner,
      String releaseVersion,
      long expectedVersion,
      List<Entry> entries,
      String artifactChecksum,
      Runnable authorizationCheck) {
    if (owner == null
        || !owner.matches("[a-z][a-z0-9-]{0,127}")
        || owner.equals("identity-service")
        || releaseVersion == null
        || !releaseVersion.matches("[a-zA-Z0-9][a-zA-Z0-9._+-]{0,127}")
        || expectedVersion < 0
        || entries == null) {
      throw invalid("发布归属或版本不合法");
    }
    String publisher = publisher(owner);
    Map<String, PermissionCatalogMapper.Row> proposed = new TreeMap<>();
    for (Entry entry : entries) {
      var row = normalize(owner, entry);
      if (proposed.putIfAbsent(row.code(), row) != null) {
        throw invalid("清单含重复权限编码");
      }
    }
    String digest = digest(proposed.values().stream().toList());
    return transactions.execute(
        status -> {
          long current = mapper.lockVersion();
          authorizationCheck.run();
          if (current != expectedVersion) {
            throw conflict("CATALOG_VERSION_CONFLICT", "目录已变化，请刷新后发布");
          }
          var all =
              mapper.all().stream()
                  .collect(Collectors.toMap(PermissionCatalogMapper.Row::code, row -> row));
          List<PermissionCatalogMapper.Row> changes = new ArrayList<>();
          for (var row : proposed.values()) {
            var old = all.get(row.code());
            if (old != null) {
              if (!old.ownerService().equals(owner)) {
                throw conflict("PERMISSION_OWNER_CONFLICT", "权限编码已由其他服务拥有");
              }
              if (!sameMeaning(old, row)) {
                throw conflict("PERMISSION_SEMANTICS_CONFLICT", "权限语义变化必须使用新编码");
              }
              if (!old.status().equals(row.status())
                  && row.status().equals("DISABLED")
                  && !old.status().equals("DEPRECATED")) {
                throw invalid("权限须先标记废弃，再显式停用");
              }
            } else if (row.status().equals("DISABLED")) {
              throw invalid("尚未发布的权限不能直接标记停用");
            }
            if (old == null || !samePresentation(old, row)) {
              changes.add(row);
            }
          }
          Map<String, PermissionCatalogMapper.Row> finalOwned =
              all.values().stream()
                  .filter(row -> row.ownerService().equals(owner))
                  .collect(Collectors.toMap(PermissionCatalogMapper.Row::code, row -> row));
          finalOwned.putAll(proposed);
          coherent(finalOwned.values().stream().toList());
          if (!changes.isEmpty()) {
            changes.forEach(mapper::save);
            if (mapper.advance(current) != 1) {
              throw conflict("CATALOG_VERSION_CONFLICT", "目录已变化，请刷新后发布");
            }
          }
          long publishedVersion = current + (changes.isEmpty() ? 0 : 1);
          Map<String, Object> audit =
              new HashMap<>(
                  Map.of(
                      "publisher_client_id",
                      publisher,
                      "release_version",
                      releaseVersion,
                      "changed_codes",
                      changes.stream().map(PermissionCatalogMapper.Row::code).sorted().toList(),
                      "catalog_version",
                      publishedVersion,
                      "source_digest",
                      digest));
          if (artifactChecksum != null) {
            audit.put("artifact_checksum", artifactChecksum);
          }
          mapper.audit(owner, CallContexts.current().requestId(), JSON.writeValueAsString(audit));
          return new Result(publishedVersion, !changes.isEmpty(), digest);
        });
  }

  private static String publisher(String owner) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    var context = CallContexts.current();
    if (authentication == null
        || !authentication.isAuthenticated()
        || context == null
        || context.tenantId() != null
        || !(authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal)
        || !"service".equals(principal.getAttribute("principal_type"))
        || principal.getAttribute("tenant_id") != null
        || !authentication.getName().equals(context.principalId())) {
      throw new PlatformException("INVALID_CREDENTIALS", "需要受控项目级发布身份", 401);
    }
    if (authentication.getAuthorities().stream()
        .noneMatch(
            authority -> authority.getAuthority().equals("SCOPE_catalog.publish:" + owner))) {
      throw new PlatformException("CATALOG_PUBLISH_FORBIDDEN", "发布身份未获准管理该服务目录", 403);
    }
    return authentication.getName();
  }

  private static PermissionCatalogMapper.Row normalize(String owner, Entry value) {
    if (value == null
        || value.code() == null
        || !value.code().matches("[a-z][a-z0-9:_-]{0,159}")
        || value.code().startsWith("identity:")
        || value.code().startsWith("platform:")
        || value.principalType() != PrincipalType.TENANT
        || value.status() == null
        || value.dataScopes() == null
        || value.dataScopes().stream().anyMatch(Objects::isNull)) {
      throw invalid("业务发布只接受合法、非保留的 TENANT 权限");
    }
    String group = value.groupCode();
    String groupName = value.groupName();
    if (group == null && groupName == null) {
      group = owner;
      groupName = owner;
    }
    identifier(group);
    identifier(value.resourceCode());
    identifier(value.actionCode());
    label(value.name());
    label(groupName);
    label(value.resourceName());
    if (value.oauthScope() == null || !value.oauthScope().matches("[a-zA-Z0-9._:-]{1,160}")) {
      throw invalid("OAuth scope 声明不合法");
    }
    String description = value.description() == null ? "" : value.description();
    if (description.contains("<") || description.contains(">")) {
      throw invalid("目录说明只接受纯文本");
    }
    String scopes =
        value.dataScopes().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    return new PermissionCatalogMapper.Row(
        value.code(),
        value.name(),
        owner,
        "TENANT",
        group,
        groupName,
        value.resourceCode(),
        value.resourceName(),
        value.actionCode(),
        description,
        value.sort(),
        scopes,
        value.oauthScope(),
        value.status().name());
  }

  private static boolean sameMeaning(
      PermissionCatalogMapper.Row old, PermissionCatalogMapper.Row next) {
    return old.principalType().equals(next.principalType())
        && old.resourceCode().equals(next.resourceCode())
        && old.actionCode().equals(next.actionCode())
        && old.oauthScope().equals(next.oauthScope())
        && scopeSet(old.scopes()).equals(scopeSet(next.scopes()));
  }

  private static Set<String> scopeSet(String raw) {
    return raw == null || raw.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(raw.split(",")));
  }

  private static boolean samePresentation(
      PermissionCatalogMapper.Row old, PermissionCatalogMapper.Row next) {
    return old.name().equals(next.name())
        && old.groupCode().equals(next.groupCode())
        && old.groupName().equals(next.groupName())
        && old.resourceName().equals(next.resourceName())
        && old.description().equals(next.description())
        && old.sort() == next.sort()
        && old.status().equals(next.status());
  }

  private static void coherent(List<PermissionCatalogMapper.Row> rows) {
    Map<String, Group> groups = new HashMap<>();
    Map<String, String> resources = new HashMap<>();
    Set<String> operations = new HashSet<>();
    for (var row : rows) {
      var group = new Group(row.groupName(), row.sort());
      var oldGroup = groups.putIfAbsent(row.groupCode(), group);
      if (oldGroup != null && !oldGroup.equals(group)) {
        throw invalid("同服务分组的名称和排序必须一致");
      }
      var oldResource = resources.putIfAbsent(row.resourceCode(), row.resourceName());
      if (oldResource != null && !oldResource.equals(row.resourceName())) {
        throw invalid("同一资源类型的名称必须一致");
      }
      if (!operations.add(row.resourceCode() + ":" + row.actionCode())) {
        throw invalid("同服务资源操作必须唯一");
      }
    }
  }

  private static void identifier(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9_-]{0,127}")) {
      throw invalid("目录分组、资源或操作编码不合法");
    }
  }

  private static void label(String value) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > 128
        || value.contains("<")
        || value.contains(">")
        || value.chars().anyMatch(Character::isISOControl)) {
      throw invalid("目录显示名称必须是受限长度的纯文本");
    }
  }

  private static String digest(List<PermissionCatalogMapper.Row> rows) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(JSON.writeValueAsString(rows).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
    }
  }

  private static PlatformException invalid(String message) {
    return new PlatformException("PERMISSION_CATALOG_INVALID", message, 400);
  }

  private static PlatformException conflict(String code, String message) {
    return new PlatformException(code, message, 409);
  }
}
