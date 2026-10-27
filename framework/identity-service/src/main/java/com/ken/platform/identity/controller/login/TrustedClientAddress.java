package com.ken.platform.identity.controller.login;

import com.ken.platform.foundation.http.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/** 仅登记的实际代理链可提供 X-Forwarded-For；从右向左停止于第一个非代理地址。 */
public final class TrustedClientAddress {
  private final List<IpAddressMatcher> proxies;

  public TrustedClientAddress(String configured) {
    var result = new ArrayList<IpAddressMatcher>();
    if (configured != null && !configured.isBlank()) {
      for (String item : configured.split(",", -1)) {
        String[] pieces = item.strip().split("/", -1);
        if (pieces.length > 2) throw new IllegalArgumentException("可信代理 CIDR 不合法");
        InetAddress address = parse(pieces[0]);
        int max = address.getAddress().length * 8;
        int mask = pieces.length == 1 ? max : Integer.parseInt(pieces[1]);
        if (mask <= 0 || mask > max) throw new IllegalArgumentException("可信代理范围不能覆盖整个互联网");
        result.add(new IpAddressMatcher(address.getHostAddress() + "/" + mask));
      }
    }
    proxies = List.copyOf(result);
  }

  public InetAddress resolve(HttpServletRequest request) {
    InetAddress current = parse(request.getRemoteAddr());
    if (!trusted(current)) return current;
    var chain = new ArrayList<InetAddress>();
    var headers = request.getHeaders("X-Forwarded-For");
    while (headers.hasMoreElements()) {
      for (String item : headers.nextElement().split(",", -1)) chain.add(parse(item.strip()));
    }
    if (chain.isEmpty()) throw unavailable();
    for (int i = chain.size() - 1; i >= 0 && trusted(current); i--) current = chain.get(i);
    return current;
  }

  private boolean trusted(InetAddress address) {
    return proxies.stream().anyMatch(proxy -> proxy.matches(address.getHostAddress()));
  }

  private static InetAddress parse(String value) {
    if (value == null) throw unavailable();
    if (value.startsWith("[") && value.endsWith("]"))
      value = value.substring(1, value.length() - 1);
    if (!(value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
        || value.contains(":") && value.matches("[0-9A-Fa-f:.]+"))) throw unavailable();
    try {
      return InetAddress.getByName(value);
    } catch (java.net.UnknownHostException invalid) {
      throw unavailable();
    }
  }

  private static PlatformException unavailable() {
    return new PlatformException("LOGIN_SOURCE_UNAVAILABLE", "无法核实登录来源", 503);
  }
}
