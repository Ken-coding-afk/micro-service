package com.ken.platform.gateway.security;

import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import io.netty.util.NetUtil;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 只接受真实 TCP 对端位于登记 CIDR 内的转发头；地址解析不进行 DNS 查询。 */
@Component
public final class GatewayForwardedHeaderFilter implements WebFilter, Ordered {
  private final List<IpSubnetFilterRule> proxies;

  public GatewayForwardedHeaderFilter(Environment environment) {
    var rules = new ArrayList<IpSubnetFilterRule>();
    String configured = environment.getProperty("TRUSTED_PROXY_CIDRS", "");
    try {
      if (!configured.isBlank())
        for (String entry : configured.split(",", -1)) {
          String[] parts = entry.strip().split("/", -1);
          if (parts.length > 2) throw invalid();
          byte[] bytes = NetUtil.createByteArrayFromIpAddressString(parts[0]);
          if (bytes == null) throw invalid();
          InetAddress address = InetAddress.getByAddress(bytes);
          int bits = address.getAddress().length * 8;
          if (parts.length == 2 && !parts[1].matches("[0-9]{1,3}")) throw invalid();
          int mask = parts.length == 1 ? bits : Integer.parseInt(parts[1]);
          if (mask <= 0 || mask > bits) throw invalid();
          rules.add(new IpSubnetFilterRule(address, mask, IpFilterRuleType.ACCEPT));
        }
    } catch (Exception failure) {
      throw invalid();
    }
    proxies = List.copyOf(rules);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    var peer = exchange.getRequest().getRemoteAddress();
    boolean trusted =
        peer != null
            && peer.getAddress() != null
            && proxies.stream().anyMatch(rule -> rule.matches(peer));
    if (trusted) return chain.filter(exchange);
    var request =
        exchange
            .getRequest()
            .mutate()
            .headers(
                headers -> {
                  for (String original : List.copyOf(headers.headerNames())) {
                    String key = original.toLowerCase(Locale.ROOT);
                    if (key.equals("forwarded")
                        || key.startsWith("x-forwarded-")
                        || key.equals("x-real-ip")
                        || key.equals("front-end-https")) headers.remove(original);
                  }
                })
            .build();
    return chain.filter(exchange.mutate().request(request).build());
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Gateway 可信代理必须是明确 IP/CIDR，不能信任整个互联网");
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 2;
  }
}
