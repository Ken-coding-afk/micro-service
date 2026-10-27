package com.ken.platform.gateway.bff;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.NacosRuntime;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.discovery.ServiceInstance;
import com.ken.platform.gateway.RouteFixtures;
import com.ken.platform.gateway.config.ExternalBoundaryFilter;
import com.ken.platform.gateway.security.GatewayForwardedHeaderFilter;
import com.ken.platform.gateway.security.GatewayPrincipalContextFilter;
import com.ken.platform.gateway.security.GatewaySecurityConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.reactive.context.AnnotationConfigReactiveWebServerApplicationContext;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

/** 真实身份 HTTP/Redis/Gateway 联调；固定目录以实际 HTTP 确认，不冒充 Nacos 多机注册。 */
public final class GatewayBffProbe {
  private static final AtomicReference<Instant> TOKEN_NOW = new AtomicReference<>();
  private static final Clock TOKEN_CLOCK =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          Instant fixed = TOKEN_NOW.get();
          return fixed == null ? Instant.now() : fixed;
        }
      };

  @Configuration(proxyBeanMethods = false)
  static class ProbeBffConfiguration extends BffConfiguration {
    @Override
    @Bean
    BffTokens bffTokens(
        BffSessions sessions,
        org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
            repository,
        org.springframework.data.redis.core.ReactiveStringRedisTemplate locks,
        org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
            registrations,
        BffIdentityClient identity,
        RuntimeParameters parameters,
        Clock clock,
        Environment environment) {
      // 仅刷新决策使用可推进时钟；HTTP 预算、SSO/身份生产者和 token 原值均不修改。
      return super.bffTokens(
          sessions,
          repository,
          locks,
          registrations,
          identity,
          parameters,
          TOKEN_CLOCK,
          environment);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @EnableWebFluxSecurity
  @Import({
    GatewaySecurityConfiguration.class,
    ProbeBffConfiguration.class,
    com.ken.platform.gateway.traffic.GatewayRedisConfiguration.class,
    BffEndpoints.class,
    ExternalBoundaryFilter.class,
    GatewayForwardedHeaderFilter.class,
    GatewayPrincipalContextFilter.class
  })
  @LoadBalancerClient(name = "identity-service", configuration = FixedDirectory.class)
  static class Fixture {
    @Bean
    RuntimeParameters platformParameters() {
      return RouteFixtures.parameters(
          RouteFixtures.route(
              "identity-api", "/api/v1/identity/**", "identity-service", "identity-service"));
    }

    @Bean
    org.springframework.cloud.gateway.config.GatewayProperties gatewayProperties(
        RuntimeParameters parameters) {
      return RouteFixtures.properties(parameters);
    }

    @Bean
    ServiceDiscovery platformDiscovery(Environment environment) {
      var directory = new ServiceDiscovery(System::nanoTime);
      confirm(directory, URI.create(environment.getRequiredProperty("probe.identity-origin")));
      return directory;
    }

    @Bean
    NacosRuntime runtime(ServiceDiscovery directory) {
      return new NacosRuntime(null, null, null, directory);
    }

    @Bean
    HttpClientCustomizer noAutomaticReplay(RuntimeParameters parameters) {
      return client ->
          client
              .disableRetry(true)
              .followRedirect(false)
              .option(
                  io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                  (int) (parameters.connectTimeoutSeconds() * 1000));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class FixedDirectory {
    @Bean
    ServiceInstanceListSupplier identityInstances(Environment environment) {
      var target = URI.create(environment.getRequiredProperty("probe.identity-origin"));
      return new ServiceInstanceListSupplier() {
        @Override
        public String getServiceId() {
          return "identity-service";
        }

        @Override
        public Flux<List<org.springframework.cloud.client.ServiceInstance>> get() {
          return Flux.just(
              List.of(
                  new DefaultServiceInstance(
                      "identity-bff-live-test",
                      "identity-service",
                      target.getHost(),
                      target.getPort(),
                      false)));
        }
      };
    }
  }

  private static void confirm(ServiceDiscovery directory, URI target) {
    directory.confirm(
        "identity-service",
        List.of(new ServiceInstance(target.getHost(), target.getPort(), "http")));
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("需要隔离 BFF 测试绑定");
    var json = JsonMapper.builder().build();
    var binding = json.readTree(Files.readString(Path.of(args[0])));
    if (!binding.get("test_only").asBoolean())
      throw new IllegalArgumentException("只允许 test_only 绑定");
    var identity = URI.create(binding.get("identity_origin").asString());
    var origin = URI.create(binding.get("public_origin").asString());
    if (!"127.0.0.1".equals(identity.getHost())
        || !"http".equals(identity.getScheme())
        || !"localhost".equals(origin.getHost())
        || !"http".equals(origin.getScheme())
        || origin.getPort() < 1) throw new IllegalArgumentException("仅用于 loopback HTTP 联调");
    var redis = json.readTree(Files.readString(Path.of(binding.get("redis_binding").asString())));
    if (!redis.get("test_only").asBoolean() || !"local".equals(redis.get("app_env").asString()))
      throw new IllegalArgumentException("需要隔离 Redis 绑定");
    try (var application = new AnnotationConfigReactiveWebServerApplicationContext();
        var http = HttpClient.newHttpClient()) {
      application
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "bff-live-probe",
                  Map.ofEntries(
                      Map.entry("PROJECT_CODE", binding.get("project").asString()),
                      Map.entry("APP_ENV", "local"),
                      Map.entry("PUBLIC_ORIGIN", origin.toString()),
                      Map.entry("SERVICE_NAME", "api-gateway"),
                      Map.entry("server.address", "127.0.0.1"),
                      Map.entry("server.port", Integer.toString(origin.getPort())),
                      Map.entry("server.forward-headers-strategy", "none"),
                      Map.entry("spring.cloud.nacos.discovery.enabled", "false"),
                      Map.entry("spring.cloud.nacos.config.enabled", "false"),
                      Map.entry(
                          "spring.cloud.gateway.server.webflux.discovery.locator.enabled", "false"),
                      Map.entry(
                          "spring.autoconfigure.exclude",
                          "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"),
                      Map.entry("probe.identity-origin", identity.toString()),
                      Map.entry("REDIS_HOST", redis.get("host").asString()),
                      Map.entry("REDIS_PORT", Integer.toString(redis.get("port").asInt())),
                      Map.entry(
                          "REDIS_PASSWORD_SECRET_FILE", redis.get("password_file").asString()),
                      Map.entry(
                          "GATEWAY_BFF_CLIENTS_SECRET_FILE",
                          binding.get("bff_clients_file").asString()),
                      Map.entry(
                          "GATEWAY_INTROSPECTION_CLIENTS_SECRET_FILE",
                          binding.get("readers_file").asString()))));
      application.register(Fixture.class);
      application.refresh();
      var settings = application.getBean(BffClientSettings.class);
      for (var namespace : settings.namespaces())
        application
            .getBean(BffIdentityClient.class)
            .verify(settings.client(namespace))
            .block(Duration.ofSeconds(5));
      try (var background = Executors.newSingleThreadScheduledExecutor()) {
        background.scheduleAtFixedRate(
            () ->
                http.sendAsync(
                        HttpRequest.newBuilder(identity.resolve("/oauth2/jwks"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.discarding())
                    .thenAccept(
                        response -> {
                          if (response.statusCode() == 200)
                            confirm(application.getBean(ServiceDiscovery.class), identity);
                        }),
            5,
            10,
            TimeUnit.SECONDS);
        if (binding.has("token_clock_file")) {
          Path clockFile = Path.of(binding.get("token_clock_file").asString());
          background.scheduleAtFixedRate(
              () -> {
                try {
                  if (Files.isRegularFile(clockFile)) {
                    Instant value = Instant.parse(Files.readString(clockFile).strip());
                    TOKEN_NOW.set(value);
                    Files.writeString(Path.of(clockFile.toString() + ".applied"), value.toString());
                  }
                } catch (Exception unreadable) {
                  /* 不改变旧的已知时钟值；主测试有明确超时断言。 */
                }
              },
              0,
              50,
              TimeUnit.MILLISECONDS);
        }
        Files.writeString(
            Path.of(binding.get("ready_file").asString()),
            json.writeValueAsString(Map.of("test_only", true, "origin", origin.toString())));
        System.in.read();
      }
    }
  }
}
