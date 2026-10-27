package com.ken.platform.identity.dal.mapper.oauth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 官方 JDBC 格式原样保留；事务内按授权行加锁，再按原 token 重读，避免消费陈旧快照。 */
public final class AuthorizationStore extends JdbcOAuth2AuthorizationService {
  private final JdbcTemplate jdbc;

  public AuthorizationStore(JdbcTemplate jdbc, RegisteredClientRepository clients) {
    super(jdbc, clients);
    this.jdbc = jdbc;
    var validator =
        tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator.builder()
            .allowIfSubType(
                com.ken.platform.identity.service.login.PasswordLoginService.Identity.class)
            .allowIfSubType(com.ken.platform.identity.dal.dataobject.login.LoginRealm.class)
            .allowIfSubType(java.util.UUID.class)
            .allowIfSubType(Long.class)
            .allowIfSubType(Integer.class);
    var mapper =
        tools.jackson.databind.json.JsonMapper.builder()
            .addModules(
                org.springframework.security.jackson.SecurityJacksonModules.getModules(
                    AuthorizationStore.class.getClassLoader(), validator))
            .build();
    setAuthorizationRowMapper(new JsonMapperOAuth2AuthorizationRowMapper(clients, mapper));
    setAuthorizationParametersMapper(new JsonMapperOAuth2AuthorizationParametersMapper(mapper));
  }

  /** 只定位可信绑定，不先取得授权锁；写入口按目录/客户端/账号/会话/授权的顺序加锁。 */
  public OAuth2Authorization observeByToken(String token, OAuth2TokenType type) {
    return super.findByToken(token, type);
  }

  public java.util.List<String> lockSessionAuthorizations(java.util.UUID session) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("撤销授权必须位于事务内");
    return jdbc.queryForList(
        "SELECT id FROM oauth2_authorization WHERE attributes::jsonb->>'platform.issued.session'=? ORDER BY id FOR UPDATE",
        String.class,
        session.toString());
  }

  public java.util.List<String> lockApplicationAuthorizations(
      com.ken.platform.identity.service.login.PasswordLoginService.Identity owner, String client) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("撤销授权必须位于事务内");
    return jdbc.queryForList(
        """
        SELECT a.id FROM oauth2_authorization a JOIN sys_login_session s
          ON a.attributes::jsonb->>'platform.issued.session'=s.id::text
        WHERE a.registered_client_id=? AND s.realm=? AND s.tenant_id IS NOT DISTINCT FROM ?
          AND COALESCE(s.user_id,s.platform_user_id)=? ORDER BY a.id FOR UPDATE OF a
        """,
        String.class,
        client,
        owner.realm().name(),
        owner.tenant(),
        owner.user());
  }

  @Override
  public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
    var found = super.findByToken(token, type);
    if (found == null || !TransactionSynchronizationManager.isActualTransactionActive())
      return found;
    jdbc.queryForList(
        "SELECT id FROM oauth2_authorization WHERE id=? FOR UPDATE", String.class, found.getId());
    return super.findByToken(token, type);
  }
}
