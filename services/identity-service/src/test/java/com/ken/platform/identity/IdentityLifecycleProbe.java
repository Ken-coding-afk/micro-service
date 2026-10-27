package com.ken.platform.identity;

import org.springframework.boot.SpringApplication;

/** 仅集成测试从 stdin 触发正常关闭；启动类和运行依赖仍取实际打包制品。 */
public final class IdentityLifecycleProbe {
  public static void main(String[] args) throws Exception {
    try (var application = SpringApplication.run(IdentityApplication.class, args)) {
      System.in.read();
    }
  }
}
