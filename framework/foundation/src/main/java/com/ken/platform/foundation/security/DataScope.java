package com.ken.platform.foundation.security;

/** 不适用使用 null，不能把 null 解释成 TENANT。 */
public enum DataScope {
  SELF,
  TENANT
}
