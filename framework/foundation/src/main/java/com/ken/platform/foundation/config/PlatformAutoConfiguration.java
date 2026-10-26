package com.ken.platform.foundation.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * foundation 公共装配入口。
 *
 * <p>T-01 骨架阶段仅建立自动配置链路与包扫描；身份内省、租户上下文、HTTP/数据/消息
 * 适配在 T-03A/T-05A/T-06 按设计逐包补齐。技术依赖在本模块全部 optional，由消费方
 * 显式引入后触发对应条件装配。
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.ken.platform.foundation")
public class PlatformAutoConfiguration {
}
