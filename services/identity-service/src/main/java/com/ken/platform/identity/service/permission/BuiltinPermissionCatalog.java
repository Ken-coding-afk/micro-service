package com.ken.platform.identity.service.permission;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.permission.PermissionCatalogMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 固定发行目录的维护用例；仅由 bootstrap 调用，不注册 HTTP 入口或接受外部清单。 */
public final class BuiltinPermissionCatalog {
  public static final List<String> PROTECTED_TENANT_PERMISSIONS =
      List.of(
          "identity:group:read",
          "identity:group:manage",
          "identity:group:assign",
          "identity:user:read",
          "identity:user:manage",
          "identity:role:read",
          "identity:role:manage",
          "identity:role:assign",
          "identity:permission:read",
          "identity:session:read",
          "identity:session:manage",
          "identity:audit:read");

  public record Result(long catalogVersion, List<String> changedCodes) {
    public Result {
      changedCodes = List.copyOf(changedCodes);
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final PermissionCatalogMapper mapper;
  private final TransactionTemplate transactions;

  public BuiltinPermissionCatalog(
      PermissionCatalogMapper mapper, PlatformTransactionManager manager) {
    this.mapper = mapper;
    transactions = new TransactionTemplate(manager);
    transactions.setTimeout(30);
  }

  public Result install() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("内置目录维护必须拥有独立事务");
    }
    return transactions.execute(
        transaction -> {
          long version = mapper.lockVersion();
          var all = mapper.all();
          var byCode =
              all.stream()
                  .collect(
                      Collectors.toMap(PermissionCatalogMapper.Row::code, Function.identity()));
          List<PermissionCatalogMapper.Row> changes = new ArrayList<>();
          for (var row : entries()) {
            var old = byCode.get(row.code());
            if (old != null && !sameMeaning(old, row)) {
              throw conflict();
            }
            if (all.stream()
                .anyMatch(
                    value ->
                        !value.code().equals(row.code())
                            && value.ownerService().equals(row.ownerService())
                            && value.resourceCode().equals(row.resourceCode())
                            && value.actionCode().equals(row.actionCode()))) {
              throw conflict();
            }
            // 不恢复已停用项，也不重新解释其用途；须先明确修订发行合同。
            if (old != null && !old.status().equals("ACTIVE")) throw conflict();
            if (!row.equals(old)) changes.add(row);
          }
          if (!changes.isEmpty()) {
            changes.forEach(mapper::save);
            if (mapper.advance(version) != 1) throw conflict();
            mapper.audit(
                "identity-service",
                UUID.randomUUID().toString(),
                JSON.writeValueAsString(
                    Map.of(
                        "source",
                        "BUILTIN_RELEASE",
                        "catalog_version",
                        version + 1,
                        "changed_codes",
                        changes.stream()
                            .map(PermissionCatalogMapper.Row::code)
                            .sorted()
                            .toList())));
          }
          return new Result(
              version + (changes.isEmpty() ? 0 : 1),
              changes.stream().map(PermissionCatalogMapper.Row::code).sorted().toList());
        });
  }

  public static List<PermissionCatalogMapper.Row> entries() {
    List<PermissionCatalogMapper.Row> rows = new ArrayList<>();
    tenant(rows, "group", "用户组", "identity.groups", "read", "manage", "assign");
    tenant(rows, "user", "用户", "identity.users", "read", "manage");
    tenant(rows, "role", "角色", "identity.roles", "read", "manage", "assign");
    tenant(rows, "permission", "权限目录", "identity.permissions", "read");
    tenant(rows, "session", "登录会话", "identity.sessions", "read", "manage");
    tenant(rows, "application", "应用", "identity.applications", "read", "manage");
    tenant(rows, "provider", "外部提供方", "identity.providers", "read", "manage");
    tenant(rows, "security", "安全策略", "identity.security", "read", "manage");
    tenant(rows, "audit", "审计", "identity.audit", "read");
    platform(rows, "admin", "平台管理员", "platform.admins", "read", "manage");
    platform(rows, "permission", "业务权限目录", "platform.permissions", "read", "manage");
    platform(rows, "identity", "租户身份管理", "platform.identity", "read", "manage");
    platform(rows, "tenant", "租户", "platform.tenants", "read", "manage");
    platform(rows, "client", "客户端", "platform.clients", "read", "manage");
    platform(rows, "security", "平台安全策略", "platform.security", "read", "manage");
    platform(rows, "audit", "平台审计", "platform.audit", "read");
    platform(rows, "monitoring", "服务监控", "platform.monitoring", "read");
    return List.copyOf(rows);
  }

  private static void tenant(
      List<PermissionCatalogMapper.Row> rows,
      String resource,
      String name,
      String scope,
      String... actions) {
    add(rows, "identity", "TENANT", resource, resource, name, scope, "TENANT", actions);
  }

  private static void platform(
      List<PermissionCatalogMapper.Row> rows,
      String resource,
      String name,
      String scope,
      String... actions) {
    add(rows, "platform", "PLATFORM", "platform-" + resource, resource, name, scope, "", actions);
  }

  private static void add(
      List<PermissionCatalogMapper.Row> rows,
      String namespace,
      String type,
      String resource,
      String codeResource,
      String name,
      String scope,
      String dataScope,
      String... actions) {
    for (String action : actions) {
      String verb =
          switch (action) {
            case "read" -> "查看";
            case "manage" -> "管理";
            case "assign" -> "分配";
            default -> throw new IllegalArgumentException("未知内置操作");
          };
      rows.add(
          new PermissionCatalogMapper.Row(
              namespace + ":" + codeResource + ":" + action,
              verb + name,
              "identity-service",
              type,
              namespace,
              type.equals("TENANT") ? "租户身份管理" : "平台管理",
              resource,
              name,
              action,
              verb + name,
              type.equals("TENANT") ? 0 : 100,
              dataScope,
              scope,
              "ACTIVE",
              "BUILTIN"));
    }
  }

  private static boolean sameMeaning(
      PermissionCatalogMapper.Row old, PermissionCatalogMapper.Row row) {
    return old.source().equals("BUILTIN")
        && old.ownerService().equals(row.ownerService())
        && old.principalType().equals(row.principalType())
        && old.resourceCode().equals(row.resourceCode())
        && old.actionCode().equals(row.actionCode())
        && old.scopes().equals(row.scopes())
        && old.oauthScope().equals(row.oauthScope());
  }

  private static PlatformException conflict() {
    return new PlatformException("BUILTIN_PERMISSION_CONFLICT", "已有身份目录与固定发行合同冲突，停止初始化", 409);
  }
}
