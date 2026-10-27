package com.ken.platform.foundation.discovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.ken.platform.foundation.config.NacosSettings;
import com.ken.platform.foundation.config.RuntimeParametersTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NacosLifecycleTest {
  @TempDir Path temporary;

  private NacosSettings settings(String role) throws Exception {
    Path secret = temporary.resolve("nacos.password");
    Files.writeString(secret, "private-fixture");
    return new NacosSettings(
        "analysis-service",
        "sample-local",
        "localhost:8848",
        "runtime",
        secret,
        "192.0.2.10",
        8100,
        role,
        "http");
  }

  @Test
  void checksPrecedeRegistrationAndWorkerDoesNotRegister() throws Exception {
    for (String role : List.of("api", "worker")) {
      var config = mock(ConfigService.class);
      var naming = mock(NamingService.class);
      when(config.getConfigAndSignListener(
              eq("application-shared.yaml"), eq("DEFAULT_GROUP"), anyLong(), any()))
          .thenReturn(RuntimeParametersTest.SHARED);
      when(config.getConfigAndSignListener(
              eq("analysis-service.yaml"), eq("DEFAULT_GROUP"), anyLong(), any()))
          .thenReturn("{}");
      var runtime =
          new NacosRuntime(settings(role), naming, config, new ServiceDiscovery(System::nanoTime));
      runtime.start(
          () -> {
            assertThat(runtime.ready()).isFalse();
            verifyNoInteractions(naming);
          });
      assertThat(runtime.ready()).isTrue();
      runtime.close();
      assertThat(runtime.ready()).isFalse();
      verify(naming, times(role.equals("api") ? 1 : 0))
          .registerInstance(eq("analysis-service"), eq("DEFAULT_GROUP"), any(Instance.class));
      verify(naming, never()).shutDown();
      verify(config, never()).shutDown();
    }
  }

  @Test
  void failedConfigAndFailedNetworkNeverConfirmReadyOrNewInstances() throws Exception {
    var config = mock(ConfigService.class);
    var naming = mock(NamingService.class);
    var directory = new ServiceDiscovery(System::nanoTime);
    var runtime = new NacosRuntime(settings("api"), naming, config, directory);
    when(config.getConfigAndSignListener(anyString(), anyString(), anyLong(), any()))
        .thenReturn("client_secret: hidden");
    assertThatThrownBy(() -> runtime.start(() -> fail("不应检查依赖")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(runtime.ready()).isFalse();
    verifyNoInteractions(naming);
    when(naming.selectInstances("analysis", "DEFAULT_GROUP", true, false))
        .thenThrow(new NacosException(503, "offline"));
    assertThatThrownBy(() -> runtime.watch("analysis")).isInstanceOf(NacosException.class);
    assertThatThrownBy(() -> directory.instances("analysis"))
        .isInstanceOf(com.ken.platform.foundation.http.PlatformException.class);
    runtime.close();
  }
}
