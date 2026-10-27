package com.ken.platform.identity.controller.login;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.http.PlatformException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedClientAddressTest {
  @Test
  void untrustedPeerCannotChooseItsSourceUsingEitherForwardingHeader() {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.1");
    request.addHeader("Forwarded", "for=203.0.113.8");
    request.addHeader("X-Forwarded-For", "malformed,203.0.113.9");
    assertThat(new TrustedClientAddress("10.0.0.1/32").resolve(request).getHostAddress())
        .isEqualTo("198.51.100.1");
  }

  @Test
  void registeredChainStopsAtNearestUntrustedAddress() {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.3");
    request.addHeader("X-Forwarded-For", "203.0.113.12, 198.51.100.8, 10.0.0.2");
    assertThat(
            new TrustedClientAddress("10.0.0.2/32,10.0.0.3/32").resolve(request).getHostAddress())
        .isEqualTo("198.51.100.8");
  }

  @Test
  void ipv6LiteralIsSupportedAndTrustedMissingOrNonliteralForwardingFailsClosed() {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("2001:db8::3");
    var source = new TrustedClientAddress("2001:db8::3/128");
    assertThatThrownBy(() -> source.resolve(request))
        .isInstanceOfSatisfying(
            PlatformException.class, e -> assertThat(e.status()).isEqualTo(503));
    request.addHeader("X-Forwarded-For", "[2001:db8::7]");
    assertThat(source.resolve(request).getHostAddress()).contains("2001:db8");
    var bad = new MockHttpServletRequest();
    bad.setRemoteAddr("2001:db8::3");
    bad.addHeader("X-Forwarded-For", "name.example");
    assertThatThrownBy(() -> source.resolve(bad)).isInstanceOf(PlatformException.class);
  }

  @Test
  void wholeInternetOrHostnamesCannotBeRegisteredAsTrustedProxy() {
    for (String value : java.util.List.of("0.0.0.0/0", "::/0", "127.0.0.1/33", "name.example/32")) {
      assertThatThrownBy(() -> new TrustedClientAddress(value))
          .isInstanceOf(RuntimeException.class);
    }
  }
}
