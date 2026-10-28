package com.ken.platform.identity.controller.support;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IdentityApiResponsesTest {
  @Test
  void successAndErrorsShareRequestIdAndNeverCache() {
    for (int status : new int[] {200, 401, 403}) {
      var response =
          IdentityApiResponses.response(status, status == 200 ? "OK" : "REJECTED", "message", null);
      assertThat(response.getStatusCode().value()).isEqualTo(status);
      assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
      assertThat(response.getBody())
          .containsEntry("data", null)
          .containsEntry("request_id", response.getHeaders().getFirst("X-Request-Id"));
      if (status != 200)
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).startsWith("Bearer ");
    }
  }
}
