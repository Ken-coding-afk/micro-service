package com.ken.platform.identity.service.audit;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.audit.SecurityAuditRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 管理入口必须拥有事务边界；业务事务结束后独立记录失败尝试，不把异常正文作为审计数据。 */
@Service
public final class ManagementAudits {
  public enum Action {
    TENANT_UPDATE("platform.tenant.updated"),
    ADMINISTRATOR_UPDATE("platform.admin.updated"),
    TENANT_CREATE("platform.tenant.created"),
    TENANT_USER_CREATE("identity.user.created"),
    PLATFORM_ADMIN_CREATE("platform.admin.created"),
    USER_STATUS("identity.user.status"),
    USER_PROFILE("identity.user.profile.updated"),
    SESSION_REVOKE("identity.managed-session.revoked"),
    USER_SIGN_OUT_ALL("identity.user.signed-out-all"),
    ROLE_CREATE("identity.role.created"),
    ROLE_UPDATE("identity.role.updated"),
    ROLE_COPY("identity.role.copied"),
    ROLE_DELETE("identity.role.deleted"),
    ROLE_PERMISSIONS("identity.role.permissions"),
    ROLE_ASSIGNMENT("identity.user.roles"),
    MANAGEMENT_GRANT("identity.management-grant.updated"),
    API_REQUEST("identity.api.rejected"),
    CATALOG_PUBLISH("identity.permissions.published"),
    SERVICE_REGISTER("platform.service.registered"),
    CLIENT_CREATE("identity.client.created"),
    CLIENT_UPDATE("identity.client.updated"),
    PLATFORM_SECURITY_POLICY("platform.security.policy.updated"),
    TENANT_SECURITY_POLICY("identity.security.policy.updated");
    private final String code;

    Action(String code) {
      this.code = code;
    }
  }

  private record Actor(UUID id, String type, UUID tenant, String client) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final SecurityAuditRepository repository;
  private final TransactionTemplate events;

  public ManagementAudits(SecurityAuditRepository repository, PlatformTransactionManager manager) {
    this.repository = repository;
    this.events = new TransactionTemplate(manager);
    this.events.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.events.setTimeout(5);
  }

  public <T> T attempt(Action action, UUID target, Supplier<T> operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("管理入口必须在外层事务之外执行");
    }
    var context = CallContexts.current();
    String request = context == null ? UUID.randomUUID().toString() : context.requestId();
    Actor actor = actor();
    try {
      return operation.get();
    } catch (RuntimeException failure) {
      String code =
          failure instanceof PlatformException known
                  && known.code() != null
                  && known.code().matches("[A-Z][A-Z0-9_]{0,127}")
              ? ((PlatformException) failure).code()
              : "INTERNAL_ERROR";
      Map<String, Object> details = new HashMap<>(Map.of("error_code", code));
      if (actor.client() != null) {
        details.put("client_id", actor.client());
      }
      try {
        events.execute(
            status -> {
              repository.failure(
                  actor.id(),
                  actor.type(),
                  actor.tenant(),
                  action.code,
                  target,
                  request,
                  JSON.writeValueAsString(details));
              return null;
            });
      } catch (RuntimeException unavailable) {
        throw new PlatformException("SECURITY_AUDIT_UNAVAILABLE", "安全审计暂不可用", 503);
      }
      throw failure;
    }
  }

  private static Actor actor() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    var context = CallContexts.current();
    if (auth != null
        && auth.isAuthenticated()
        && context != null
        && auth.getName().equals(context.principalId())
        && auth.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal) {
      Object type = principal.getAttribute("principal_type");
      Object tenant = principal.getAttribute("tenant_id");
      if ((context.tenantId() == null && tenant == null
          || context.tenantId() != null && context.tenantId().toString().equals(tenant))) {
        try {
          if ("user".equals(type) && context.tenantId() != null) {
            return new Actor(UUID.fromString(auth.getName()), "TENANT", context.tenantId(), null);
          }
          if ("platform".equals(type) && context.tenantId() == null) {
            return new Actor(UUID.fromString(auth.getName()), "PLATFORM", null, null);
          }
          if ("service".equals(type) && auth.getName().matches("[A-Za-z0-9._:-]{1,128}")) {
            return new Actor(null, "SERVICE", context.tenantId(), auth.getName());
          }
        } catch (IllegalArgumentException malformed) {
          /* 不将不完整身份归为有效主体。 */
        }
      }
    }
    return new Actor(null, "ANONYMOUS", null, null);
  }
}
