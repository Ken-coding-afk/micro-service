package com.ken.platform.foundation.discovery;

/** 服务实现 schema/身份等就绪检查；抛出异常将阻止实例注册。 */
@FunctionalInterface
public interface PlatformReadinessCheck {
  void verify();
}
