package com.ken.platform.foundation.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 资源路由声明；对象归属仍由项目 repository/领域规则落实。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequirePermission {
  String value();

  String scope();

  Subject subject() default Subject.USER;

  enum Subject {
    USER,
    PLATFORM,
    SERVICE
  }
}
