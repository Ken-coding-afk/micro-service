/**
 * foundation 公共包：框架的 Java 公共接入层。
 *
 * <p>按技术职责分包（config / security / tenant / web / http / data / messaging / observability），
 * 通过 Spring Boot 自动配置提供统一装配。本包 POM 中所有技术依赖均为 optional：
 * api-gateway 等纯 WebFlux 消费方引入本包时不得被带入 Servlet、JDBC 或 MyBatis
 * （《总体架构与工程结构设计·框架仓库目录》规范约束 3）。
 */
package com.ken.platform.foundation;
