package com.ken.platform.foundation.http;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class DeveloperContextTest {
  @AfterEach
  void clean() {
    reactor.core.publisher.Hooks.disableAutomaticContextPropagation();
    io.micrometer.context.ContextRegistry.getInstance()
        .removeThreadLocalAccessor(CallContext.class);
    MDC.clear();
  }

  @Test
  void lexicalAndExecutorLoggingRestoreOwnedFields() throws Exception {
    var tenant = UUID.randomUUID();
    MDC.put("custom", "preserve");
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      try (var scope =
          CallContexts.bind(
              new CallContext(
                  "request",
                  tenant,
                  "user",
                  "private-bearer",
                  null,
                  Instant.now().plusSeconds(5)))) {
        executor
            .submit(
                CallContexts.wrap(
                    () -> {
                      assertThat(MDC.get("request_id")).isEqualTo("request");
                      assertThat(MDC.get("tenant_id")).isEqualTo(tenant.toString());
                      assertThat(MDC.getCopyOfContextMap()).doesNotContainValue("private-bearer");
                    }))
            .get();
      }
      assertThat(executor.submit(() -> MDC.get("tenant_id")).get()).isNull();
    }
    assertThat(CallContexts.current()).isNull();
    assertThat(MDC.get("custom")).isEqualTo("preserve");
    assertThat(MDC.get("request_id")).isNull();
  }

  @Test
  void reactorContextSurvivesConcurrentThreadSwitchesWithoutLeaking() {
    CallContexts.enableReactorPropagation();
    var tenants = java.util.List.of(UUID.randomUUID(), UUID.randomUUID());
    var values =
        Flux.fromIterable(tenants)
            .flatMap(
                tenant ->
                    Mono.delay(Duration.ofMillis(5))
                        .publishOn(Schedulers.parallel())
                        .map(
                            ignored -> {
                              assertThat(CallContexts.current().tenantId()).isEqualTo(tenant);
                              return MDC.get("request_id") + ":" + MDC.get("tenant_id");
                            })
                        .contextWrite(
                            context ->
                                context.put(
                                    CallContext.class,
                                    new CallContext(
                                        tenant.toString(), tenant, "user", "secret", null, null))))
            .collectList()
            .block();
    assertThat(values)
        .containsExactlyInAnyOrderElementsOf(tenants.stream().map(t -> t + ":" + t).toList());
    assertThat(
            Mono.fromCallable(() -> MDC.get("tenant_id") == null)
                .subscribeOn(Schedulers.parallel())
                .block())
        .isTrue();
    assertThat(CallContexts.current()).isNull();
  }

  @Test
  void taskIdentityAndDeadlineAreExplicit() {
    try (var scope =
        CallContexts.task(UUID.randomUUID(), "equipment-service", Duration.ofSeconds(5))) {
      assertThat(CallContexts.current().accessToken()).isNull();
      assertThat(CallContexts.current().principalId()).isEqualTo("service:equipment-service");
      assertThat(CallContexts.remainingBudget(Duration.ofSeconds(2)))
          .isLessThanOrEqualTo(Duration.ofSeconds(2));
      CallContexts.checkDeadline();
      assertThatThrownBy(() -> CallContexts.task(UUID.randomUUID(), "other", Duration.ofSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(CallContexts.current()).isNull();
    try (var scope =
        CallContexts.bind(
            new CallContext("expired", null, null, null, null, Instant.now().minusSeconds(1)))) {
      assertThatThrownBy(CallContexts::checkDeadline).isInstanceOf(PlatformException.class);
      assertThatThrownBy(() -> CallContexts.remainingBudget(Duration.ofSeconds(1)))
          .isInstanceOf(PlatformException.class);
    }
  }
}
