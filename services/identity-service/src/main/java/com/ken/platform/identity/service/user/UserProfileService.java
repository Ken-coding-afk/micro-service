package com.ken.platform.identity.service.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.account.AccountFields;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.TenantManagementAuthorization;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** 通用联系资料与验证渠道隔离；完整更新不携带角色、租户、状态或凭据。 */
@Service
public final class UserProfileService {
  public record View(UUID id, long version) {}

  public record Fields(
      @JsonProperty("display_name") String displayName,
      @JsonProperty("real_name") String realName,
      @JsonProperty("employee_no") String employeeNo,
      @JsonProperty("contact_email") String contactEmail,
      String phone,
      @JsonProperty("avatar_url") String avatarUrl,
      String description) {
    public Fields normalized() {
      String name = AccountFields.displayName(displayName);
      String email = optional(contactEmail, 320),
          number = optional(phone, 32),
          avatar = optional(avatarUrl, 2048);
      if (email != null && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
        throw invalid("联系邮箱格式不合法");
      if (number != null && !number.matches("[+0-9() .-]+")) throw invalid("联系电话格式不合法");
      if (avatar != null) {
        try {
          var uri = java.net.URI.create(avatar);
          if (!(avatar.startsWith("/") && !avatar.startsWith("//") && !avatar.contains("\\")
              || "https".equals(uri.getScheme())
                  && uri.getHost() != null
                  && uri.getUserInfo() == null)) throw invalid("头像必须是HTTPS地址或同源资源路径");
        } catch (IllegalArgumentException malformed) {
          throw invalid("头像地址不合法");
        }
      }
      return new Fields(
          name,
          optional(realName, 128),
          optional(employeeNo, 64),
          email,
          number,
          avatar,
          optional(description, 1000));
    }

    private static String optional(String value, int max) {
      if (value == null || value.isBlank()) return null;
      String clean = value.strip();
      if (clean.codePointCount(0, clean.length()) > max
          || clean
              .codePoints()
              .anyMatch(
                  c ->
                      Character.isISOControl(c)
                          || c == '<'
                          || c == '>'
                          || c >= 0xD800 && c <= 0xDFFF)) throw invalid("用户资料必须是长度受限的纯文本");
      return clean;
    }

    private static PlatformException invalid(String message) {
      return new PlatformException("INVALID_ARGUMENT", message, 400);
    }
  }

  private static final String OPERATION = "identity:user:manage";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final TenantTransactions transactions;
  private final UserManagementMapper users;
  private final TenantManagementAuthorization authorization;
  private final ManagementAudits audits;
  private final Clock clock;

  public UserProfileService(
      TenantTransactions transactions,
      UserManagementMapper users,
      TenantManagementAuthorization authorization,
      ManagementAudits audits,
      Clock clock) {
    this.transactions = transactions;
    this.users = users;
    this.authorization = authorization;
    this.audits = audits;
    this.clock = clock;
  }

  public View update(UUID target, long expectedVersion, Fields requested) {
    return audits.attempt(
        ManagementAudits.Action.USER_PROFILE,
        target,
        () -> {
          Fields fields = requested.normalized();
          UUID actor = authorization.actorFor(OPERATION);
          try {
            return authorization.execute(
                transactions,
                () -> {
                  UUID tenant = authorization.tenantId();
                  authorization.lockCatalog();
                  var tenantRow = authorization.lockTenant(tenant);
                  if (tenantRow == null
                      || !tenantRow.status().equals("ACTIVE")
                      || tenantRow.expiresAt() != null
                          && !clock.instant().isBefore(tenantRow.expiresAt()))
                    throw new PlatformException("TENANT_UNAVAILABLE", "租户当前不可用", 403);
                  var ids = authorization.userIds(actor, target);
                  authorization.lockMemberRoles(tenant, ids);
                  var accounts = authorization.lockUsers(tenant, ids);
                  authorization.requireProtectedAdministrator(tenant, actor, OPERATION, accounts);
                  var account =
                      accounts.stream()
                          .filter(value -> value.id().equals(target))
                          .findFirst()
                          .orElseThrow(() -> new PlatformException("NOT_FOUND", "账号不存在或不可见", 404));
                  if (account.version() != expectedVersion) throw conflict();
                  var before = users.profile(tenant, target);
                  if (users.updateProfile(
                          tenant,
                          target,
                          expectedVersion,
                          fields.displayName(),
                          fields.realName(),
                          fields.employeeNo(),
                          fields.contactEmail(),
                          fields.phone(),
                          fields.avatarUrl(),
                          fields.description())
                      != 1) throw conflict();
                  users.auditProfile(
                      actor,
                      authorization.actorType(),
                      tenant,
                      target.toString(),
                      CallContexts.current().requestId(),
                      JSON.writeValueAsString(changes(before, fields, account.version() + 1)));
                  return new View(target, account.version() + 1);
                });
          } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new PlatformException("EMPLOYEE_NO_CONFLICT", "当前租户内工号已存在", 409);
          }
        });
  }

  private static Map<String, Object> changes(
      Map<String, String> before, Fields fields, long version) {
    var after = new java.util.LinkedHashMap<String, String>();
    after.put("display_name", fields.displayName());
    after.put("real_name", fields.realName());
    after.put("employee_no", fields.employeeNo());
    after.put("contact_email", fields.contactEmail());
    after.put("phone", fields.phone());
    after.put("avatar_url", fields.avatarUrl());
    after.put("description", fields.description());
    var previous = new java.util.LinkedHashMap<String, Object>();
    var next = new java.util.LinkedHashMap<String, Object>();
    after.forEach(
        (key, value) -> {
          if (!java.util.Objects.equals(before.get(key), value)) {
            boolean personal = !key.equals("display_name");
            previous.put(key, personal && before.get(key) != null ? "[REDACTED]" : before.get(key));
            next.put(key, personal && value != null ? "[REDACTED]" : value);
          }
        });
    return Map.of("fields", next.keySet(), "before", previous, "after", next, "version", version);
  }

  private static PlatformException conflict() {
    return new PlatformException("USER_VERSION_CONFLICT", "账号已变更，请刷新后操作", 409);
  }
}
