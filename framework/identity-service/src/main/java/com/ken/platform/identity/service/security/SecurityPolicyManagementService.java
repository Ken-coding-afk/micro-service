package com.ken.platform.identity.service.security;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.account.AccountManagementMapper;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import com.ken.platform.identity.dal.mapper.security.SecurityPolicyStore;
import com.ken.platform.identity.dal.mapper.user.UserManagementMapper;
import com.ken.platform.identity.service.audit.ManagementAudits;
import com.ken.platform.identity.service.authorization.PlatformManagementAuthorization;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 控制侧策略编辑；用同一有效策略计算规则校验，保存后运行时直接读取新版本。 */
@Service
public final class SecurityPolicyManagementService {
  public record State(
      Long version,
      Map<String, Integer> values,
      Map<String, Integer> inherited,
      SecurityPolicies.Effective effective) {
    public State {
      values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
      inherited = Collections.unmodifiableMap(new LinkedHashMap<>(inherited));
    }
  }

  private final SecurityPolicyMapper policies;
  private final SecurityPolicyStore store;
  private final UserManagementMapper users;
  private final AccountManagementMapper accounts;
  private final PlatformManagementAuthorization platform;
  private final ManagementAudits audits;
  private final TransactionTemplate transactions;

  public SecurityPolicyManagementService(
      SecurityPolicyMapper policies,
      SecurityPolicyStore store,
      UserManagementMapper users,
      AccountManagementMapper accounts,
      PlatformManagementAuthorization platform,
      ManagementAudits audits,
      PlatformTransactionManager manager) {
    this.policies = policies;
    this.store = store;
    this.users = users;
    this.accounts = accounts;
    this.platform = platform;
    this.audits = audits;
    transactions = new TransactionTemplate(manager);
    transactions.setTimeout(10);
  }

  public State readPlatform() {
    return audits.attempt(
        ManagementAudits.Action.API_REQUEST,
        null,
        () -> {
          UUID actor = platform.actorFor("platform:security:read");
          return transactions.execute(
              status -> {
                users.lockCatalog();
                platform.requireActive(actor);
                return state(base(null), null, false);
              });
        });
  }

  public State updatePlatform(long expectedVersion, Map<String, ?> input) {
    return audits.attempt(
        ManagementAudits.Action.PLATFORM_SECURITY_POLICY,
        null,
        () -> {
          var patch = parse(input, false);
          if (expectedVersion < 0) throw invalid();
          UUID actor = platform.actorFor("platform:security:manage");
          return transactions.execute(
              status -> {
                users.lockCatalog();
                platform.requireActive(actor);
                store.lock(null);
                var base = base(null);
                if (base.version() != expectedVersion) throw conflict();
                var before = values(base);
                var next = new LinkedHashMap<>(before);
                next.putAll(patch);
                var changed = row("PLATFORM", null, next, expectedVersion + 1);
                validate(changed, null);
                // 统一策略不能弱于仍留存的历史租户保护；不会自动覆盖或删除历史数据。
                for (var override : policies.tenantOverrides())
                  SecurityPolicies.requireCovered(changed, override);
                if (before.equals(next)) return state(base, null, false);
                if (store.update(null, expectedVersion, next) != 1) throw conflict();
                audit(actor, "PLATFORM", null, "platform.security.policy.updated", before, next);
                return state(changed, null, false);
              });
        });
  }

  private SecurityPolicyMapper.Row base(UUID owner) {
    return policies.current(owner).stream()
        .filter(value -> value.ownerType().equals("PLATFORM"))
        .findFirst()
        .orElseThrow(SecurityPolicyManagementService::unavailable);
  }

  private static Map<String, Integer> parse(Map<String, ?> input, boolean tenant) {
    if (input == null) throw invalid();
    Map<String, Integer> values = new LinkedHashMap<>();
    input.forEach(
        (field, value) -> {
          if (field == null
              || !SecurityPolicyStore.FIELDS.contains(field)
              || tenant && field.startsWith("login_ip_")
              || value == null && !tenant
              || value != null && !(value instanceof Integer)) throw invalid();
          values.put(field, (Integer) value);
        });
    return values;
  }

  private static void validate(SecurityPolicyMapper.Row base, SecurityPolicyMapper.Row override) {
    try {
      SecurityPolicies.effective(base, override);
    } catch (PlatformException invalid) {
      throw invalid();
    }
  }

  private static State state(
      SecurityPolicyMapper.Row base, SecurityPolicyMapper.Row override, boolean tenant) {
    var values = values(tenant ? override : base);
    if (tenant) values.keySet().removeIf(field -> field.startsWith("login_ip_"));
    Long version =
        tenant
            ? override == null ? null : Long.valueOf(override.version())
            : Long.valueOf(base.version());
    return new State(version, values, values(base), SecurityPolicies.effective(base, override));
  }

  private static Map<String, Integer> values(SecurityPolicyMapper.Row row) {
    Integer[] raw =
        row == null
            ? new Integer[9]
            : new Integer[] {
              row.passwordMinChars(),
              row.loginFailureThreshold(),
              row.loginFailureWindowSeconds(),
              row.loginLockSeconds(),
              row.loginIpAttemptLimit(),
              row.loginIpWindowSeconds(),
              row.sessionAbsoluteSeconds(),
              row.sessionIdleSeconds(),
              row.recentAuthSeconds()
            };
    Map<String, Integer> values = new LinkedHashMap<>();
    for (int i = 0; i < raw.length; i++) values.put(SecurityPolicyStore.FIELDS.get(i), raw[i]);
    return values;
  }

  private static SecurityPolicyMapper.Row row(
      String type, UUID owner, Map<String, Integer> values, long version) {
    return new SecurityPolicyMapper.Row(
        type,
        owner,
        values.get("password_min_chars"),
        values.get("login_failure_threshold"),
        values.get("login_failure_window_seconds"),
        values.get("login_lock_seconds"),
        values.get("login_ip_attempt_limit"),
        values.get("login_ip_window_seconds"),
        values.get("session_absolute_seconds"),
        values.get("session_idle_seconds"),
        values.get("recent_auth_seconds"),
        version);
  }

  private void audit(
      UUID actor,
      String type,
      UUID owner,
      String action,
      Map<String, Integer> before,
      Map<String, Integer> after) {
    accounts.audit(
        actor,
        type,
        owner,
        action,
        owner == null ? "PLATFORM" : owner.toString(),
        CallContexts.current().requestId(),
        JsonMapper.builder().build().writeValueAsString(Map.of("before", before, "after", after)));
  }

  private static PlatformException invalid() {
    return new PlatformException("SECURITY_POLICY_INVALID", "安全策略字段、范围或继承关系不合法", 400);
  }

  private static PlatformException conflict() {
    return new PlatformException("SECURITY_POLICY_VERSION_CONFLICT", "安全策略已变化，请刷新后操作", 409);
  }

  private static PlatformException unavailable() {
    return new PlatformException("SECURITY_POLICY_UNAVAILABLE", "安全策略当前不可用", 503);
  }
}
