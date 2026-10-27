package com.example.sample.client;

import com.example.sample.controller.dto.ApiResponse;
import com.example.sample.model.ItemPage;
import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.http.ServiceHttpClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class PeerClient {
  private final ServiceHttpClient http;
  private final Environment environment;

  public PeerClient(ServiceHttpClient http, Environment environment) {
    this.http = http;
    this.environment = environment;
  }

  public ResponseEntity<ApiResponse<ItemPage>> list() throws Exception {
    if (!"sample-python-service".equals(environment.getProperty("PEER_SERVICE")))
      throw new PlatformException("PEER_NOT_CONFIGURED", "未启用 Python 协作示例", 503);
    try {
      return http.request(
              "sample-python-service",
              "GET",
              "/api/v1/python-items",
              null,
              CallContexts.current(),
              new ParameterizedTypeReference<ApiResponse<ItemPage>>() {})
          .get();
    } catch (java.util.concurrent.ExecutionException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      throw failure;
    }
  }
}
