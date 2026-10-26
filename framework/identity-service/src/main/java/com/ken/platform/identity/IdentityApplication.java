package com.ken.platform.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 身份服务启动类。T-01 骨架仅验证组合构建与启动链路；
 * 账号/授权/管理能力按 T-03A/T-04A/T-07A 实施。
 */
@SpringBootApplication
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
