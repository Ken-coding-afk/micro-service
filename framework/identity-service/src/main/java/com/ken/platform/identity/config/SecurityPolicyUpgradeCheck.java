package com.ken.platform.identity.config;

import com.ken.platform.identity.dal.mapper.security.SecurityPolicyMapper;
import com.ken.platform.identity.service.security.SecurityPolicies;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动门禁：旧租户策略未统一时拒绝将新实例置为可用，不修改历史数据。 */
@Component
public final class SecurityPolicyUpgradeCheck implements ApplicationRunner {
  private final SecurityPolicyMapper mapper;

  public SecurityPolicyUpgradeCheck(SecurityPolicyMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void run(ApplicationArguments args) {
    new SecurityPolicies(mapper).current(null);
    var base = mapper.current(null).getFirst();
    for (var old : mapper.tenantOverrides()) SecurityPolicies.requireCovered(base, old);
  }
}
