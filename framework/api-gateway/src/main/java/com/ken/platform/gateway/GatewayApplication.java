package com.ken.platform.gateway;

import com.ken.platform.foundation.config.PlatformService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 统一入口启动类。T-01 骨架仅验证响应式组合构建（无 Servlet/JDBC 污染）； 路由、BFF 与入口安全按 T-04A/T-05B 实施。 */
@SpringBootApplication
@PlatformService("api-gateway")
public class GatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
