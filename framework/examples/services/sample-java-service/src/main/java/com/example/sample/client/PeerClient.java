package com.example.sample.client;

import com.ken.platform.foundation.http.CallContexts;
import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.foundation.http.ServiceHttpClient;
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

  public ResponseEntity<byte[]> list() throws Exception {
    if (!"sample-python-service".equals(environment.getProperty("PEER_SERVICE")))
      throw new PlatformException("PEER_NOT_CONFIGURED", "未启用 Python 协作示例", 503);
    return http.request(
            "sample-python-service", "GET", "/api/v1/python-items", null, CallContexts.current())
        .get();
  }
}
