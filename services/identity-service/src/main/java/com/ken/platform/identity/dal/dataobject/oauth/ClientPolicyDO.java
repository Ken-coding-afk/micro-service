package com.ken.platform.identity.dal.dataobject.oauth;

import java.util.Set;
import java.util.UUID;

/** 客户端策略的唯一数据库模型；不保存官方协议字段的副本。 */
public record ClientPolicyDO(
    PrincipalType principalType,
    UUID ownerTenantId,
    Set<UUID> allowedTenantIds,
    Set<String> audiences,
    Status status,
    long version) {
  public enum PrincipalType {
    USER,
    PLATFORM,
    SERVICE
  }

  public enum Status {
    ACTIVE,
    DISABLED
  }

  public ClientPolicyDO {
    allowedTenantIds = Set.copyOf(allowedTenantIds);
    audiences = Set.copyOf(audiences);
  }
}
