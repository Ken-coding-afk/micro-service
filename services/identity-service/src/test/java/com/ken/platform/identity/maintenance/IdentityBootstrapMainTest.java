package com.ken.platform.identity.maintenance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityBootstrapMainTest {
  @TempDir Path directory;

  @Test
  void maintenanceCreatesCorrelationWithoutInventingAnAuthenticatedPrincipal() throws Exception {
    assertThat(CallContexts.current()).isNull();
    var observed = inspectCommandContext();
    assertThat(UUID.fromString(observed.requestId())).isNotNull();
    assertThat(observed.tenantId()).isNull();
    assertThat(observed.principalId()).isNull();
    assertThat(CallContexts.current()).isNull();
  }

  @Test
  void failedMaintenanceRestoresTheCallersContext() throws Exception {
    var caller = new CallContext("outer", null, null, null, null, null);
    try (var ignored = CallContexts.bind(caller)) {
      var observed = inspectCommandContext();
      assertThat(observed.requestId()).isNotEqualTo(caller.requestId());
      assertThat(observed.tenantId()).isNull();
      assertThat(CallContexts.current()).isSameAs(caller);
    }
    assertThat(CallContexts.current()).isNull();
  }

  @Test
  void maintenanceCannotReplaceAnAuthenticatedCallersIdentity() {
    var caller = new CallContext("outer", UUID.randomUUID(), "caller", null, null, null);
    try (var scope = CallContexts.bind(caller);
        var sources = mockConstruction(HikariDataSource.class)) {
      assertThat(IdentityBootstrapMain.run(Map.of(), new String[] {"bootstrap-local"}))
          .isEqualTo(1);
      assertThat(sources.constructed()).isEmpty();
      assertThat(CallContexts.current()).isSameAs(caller);
    }
    assertThat(CallContexts.current()).isNull();
  }

  private CallContext inspectCommandContext() throws Exception {
    Path password = directory.resolve("database.secret");
    Files.writeString(password, "isolated-maintenance-fixture");
    var observed = new AtomicReference<CallContext>();
    try (var ignored =
        mockConstruction(
            HikariDataSource.class,
            (source, construction) -> observed.set(CallContexts.current()))) {
      // 到达数据源构建即观测上下文；缺少后续维护描述会受控失败，不连接真实数据库。
      assertThat(
              IdentityBootstrapMain.run(
                  Map.of(
                      "APP_ENV", "local",
                      "DB_NAME", "fixture_local_identity",
                      "DB_USER", "fixture_runtime",
                      "PG_HOST", "127.0.0.1",
                      "PG_PORT", "5432",
                      "DB_PASSWORD_SECRET_FILE", password.toString()),
                  new String[] {"bootstrap-local"}))
          .isEqualTo(1);
    }
    assertThat(observed.get()).isNotNull();
    return observed.get();
  }
}
