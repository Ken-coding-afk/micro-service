package com.ken.platform.identity.service.oauth;

import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/** 协议只读适配；完整 grant/内省约束在授权服务接入时使用同一次快照验证。 */
public final class ControlledRegisteredClients implements RegisteredClientRepository {
  private final ClientStore store;

  public ControlledRegisteredClients(ClientStore store) {
    this.store = store;
  }

  @Override
  public void save(RegisteredClient client) {
    throw new UnsupportedOperationException("客户端只能经统一管理入口写入");
  }

  @Override
  public RegisteredClient findById(String id) {
    var value = store.byId(id);
    return value == null ? null : value.client();
  }

  @Override
  public RegisteredClient findByClientId(String id) {
    var value = store.byClientId(id);
    return value == null ? null : value.client();
  }
}
