package com.ken.platform.foundation.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 制品声明的固定逻辑服务名；环境绑定必须匹配，不能让 Gateway 冒充另一服务。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PlatformService {
  String value();
}
