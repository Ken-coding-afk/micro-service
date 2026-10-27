package com.ken.platform.identity.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ken.platform.identity.dal.mapper.oauth.ClientStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalSwaggerCorsTest {
  @Test
  void onlyRegisteredLocalOriginCanPostTokenWithoutCookies() throws Exception {
    var store = mock(ClientStore.class);
    when(store.localSwaggerRedirects())
        .thenReturn(List.of("http://localhost:48083/docs/oauth2-redirect"));
    var registration = new LocalSwaggerCorsConfiguration().localSwaggerCors(store);
    assertThat(registration.getUrlPatterns()).containsExactly("/oauth2/token");
    for (String origin :
        List.of("http://localhost:48083", "http://localhost:48084", "https://evil.invalid")) {
      var request = new MockHttpServletRequest("OPTIONS", "/oauth2/token");
      request.addHeader("Origin", origin);
      request.addHeader("Access-Control-Request-Method", "POST");
      var response = new MockHttpServletResponse();
      registration.getFilter().doFilter(request, response, new MockFilterChain());
      if (origin.endsWith(":48083")) {
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
      } else {
        assertThat(response.getStatus()).isEqualTo(403);
      }
    }
  }

  @Test
  void productionNeverRegistersDevelopmentCors() {
    new ApplicationContextRunner()
        .withUserConfiguration(LocalSwaggerCorsConfiguration.class)
        .withPropertyValues("APP_ENV=prod")
        .run(context -> assertThat(context).doesNotHaveBean("localSwaggerCors"));
  }
}
