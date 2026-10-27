package com.example.sample;

import java.util.Map;

/** 此类型属于业务项目，框架不认识 max_page_size。 */
public record BusinessSettings(int maxPageSize) {
  public static BusinessSettings from(Map<String, Object> values) {
    if (!java.util.Set.of("max_page_size").containsAll(values.keySet()))
      throw new IllegalArgumentException("未知示例业务配置");
    Object size = values.getOrDefault("max_page_size", 100);
    if (!(size instanceof Integer number) || number < 20 || number > 100)
      throw new IllegalArgumentException("max_page_size 必须为 20—100 的整数");
    return new BusinessSettings(number);
  }
}
