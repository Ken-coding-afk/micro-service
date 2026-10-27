package com.ken.platform.identity.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 独立产品静态资源；未知文件仍由资源处理器返回 404，不作通配 SPA 回退。 */
@Configuration(proxyBeanMethods = false)
public class AdminWebConfiguration implements WebMvcConfigurer {
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/identity/admin/**")
        .addResourceLocations("classpath:/static/identity/admin/")
        .setCacheControl(CacheControl.noStore());
  }
}
