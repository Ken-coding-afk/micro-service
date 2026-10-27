package com.ken.platform.foundation.resilience;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 先验证整份原生 JSON，再交给 Sentinel；拒绝其静默跳过无效规则的行为。 */
public final class SentinelRules {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private SentinelRules() {}

  public static List<FlowRule> flow(String source) {
    List<FlowRule> rules = new ArrayList<>();
    for (JsonNode node :
        nodes(
            source,
            Set.of(
                "resource",
                "limitApp",
                "grade",
                "count",
                "strategy",
                "controlBehavior",
                "clusterMode"))) {
      require(node, "grade", 0);
      require(node, "strategy", 0);
      require(node, "controlBehavior", 0);
      if (node.has("clusterMode")
          && (!node.get("clusterMode").isBoolean() || node.get("clusterMode").booleanValue()))
        throw invalid();
      double count = positive(node, "count");
      if (count != Math.rint(count) || count > Integer.MAX_VALUE) throw invalid();
      var rule = new FlowRule(node.get("resource").asText());
      rule.setGrade(0);
      rule.setCount(count);
      rule.setLimitApp("default");
      rules.add(rule);
    }
    return List.copyOf(rules);
  }

  public static List<DegradeRule> degrade(String source) {
    List<DegradeRule> rules = new ArrayList<>();
    for (JsonNode node :
        nodes(
            source,
            Set.of(
                "resource",
                "limitApp",
                "grade",
                "count",
                "minRequestAmount",
                "statIntervalMs",
                "timeWindow"))) {
      require(node, "grade", 1);
      if (!node.get("resource").asText().startsWith("http.outbound/")) throw invalid();
      double count = positive(node, "count");
      if (count > 1) throw invalid();
      var rule = new DegradeRule(node.get("resource").asText());
      rule.setGrade(1);
      rule.setCount(count);
      rule.setLimitApp("default");
      rule.setMinRequestAmount(integer(node, "minRequestAmount"));
      rule.setStatIntervalMs(integer(node, "statIntervalMs"));
      rule.setTimeWindow(integer(node, "timeWindow"));
      rules.add(rule);
    }
    return List.copyOf(rules);
  }

  private static JsonNode nodes(String source, Set<String> allowed) {
    if (source == null || source.isBlank()) throw invalid();
    JsonNode root = JSON.readTree(source);
    if (!root.isArray() || root.size() > 65) throw invalid();
    Set<String> resources = new HashSet<>();
    for (JsonNode node : root) {
      if (!node.isObject() || !allowed.containsAll(node.propertyNames())) throw invalid();
      JsonNode resource = node.get("resource");
      if (resource == null
          || !resource.isTextual()
          || !resource.asText().matches("http\\.inbound|http\\.outbound/[a-z][a-z0-9-]{0,62}")
          || !resources.add(resource.asText())) throw invalid();
      if (node.has("limitApp") && !node.get("limitApp").asText().equals("default")) throw invalid();
    }
    if (resources.stream().filter(name -> name.startsWith("http.outbound/")).count() > 64)
      throw invalid();
    return root;
  }

  private static void require(JsonNode node, String key, int expected) {
    if (node.has(key)
        && (!node.get(key).isIntegralNumber() || node.get(key).intValue() != expected))
      throw invalid();
    if (key.equals("grade") && !node.has(key)) throw invalid();
  }

  private static double positive(JsonNode node, String key) {
    JsonNode value = node.get(key);
    if (value == null
        || !value.isNumber()
        || !Double.isFinite(value.doubleValue())
        || value.doubleValue() <= 0) throw invalid();
    return value.doubleValue();
  }

  private static int integer(JsonNode node, String key) {
    double value = positive(node, key);
    if (value != Math.rint(value) || value > Integer.MAX_VALUE) throw invalid();
    return (int) value;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Sentinel 规则不符合受支持的原生规则合同");
  }
}
