package com.ken.platform.foundation.security;

import com.ken.platform.foundation.config.RuntimeParameters;
import com.ken.platform.foundation.discovery.ServiceDiscovery;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import tools.jackson.databind.json.JsonMapper;

/** 每次真实内省；逻辑发现、独立 Basic 凭据、完整响应预算，不跟随重定向或重试 POST。 */
public final class ResourceIntrospector implements OpaqueTokenIntrospector, AutoCloseable {
  private final ServiceDiscovery discovery;
  private final ResourceIdentitySettings settings;
  private final RuntimeParameters parameters;
  private final Clock clock;
  private final HttpClient client;
  private final String basic;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public ResourceIntrospector(
      ServiceDiscovery discovery,
      ResourceIdentitySettings settings,
      RuntimeParameters parameters,
      Clock clock) {
    this.discovery = discovery;
    this.settings = settings;
    this.parameters = parameters;
    this.clock = clock;
    try {
      String secret = Files.readString(settings.secretFile()).strip();
      if (secret.isEmpty()) throw new IllegalArgumentException();
      basic =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      (encode(settings.clientId()) + ":" + encode(secret))
                          .getBytes(StandardCharsets.UTF_8));
    } catch (Exception invalid) {
      throw new IllegalArgumentException("资源内省秘密文件不可用");
    }
    client =
        HttpClient.newBuilder()
            .connectTimeout(
                Duration.ofMillis(Math.max(1, (long) (parameters.connectTimeoutSeconds() * 1000))))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public OAuth2AuthenticatedPrincipal introspect(String token) {
    try {
      return introspectAsync(token, CallContexts.current()).join();
    } catch (CompletionException failure) {
      if (failure.getCause() instanceof OAuth2IntrospectionException known) throw known;
      throw CurrentPrincipal.unavailable();
    }
  }

  /** 未知随机 token 必须 inactive；用于注册前核对独立客户端凭据和协议可用性。 */
  public void verifyIdentity() {
    try {
      introspect("startup-" + java.util.UUID.randomUUID());
    } catch (
        org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException
            expected) {
      return;
    }
    throw CurrentPrincipal.unavailable();
  }

  public CompletableFuture<CurrentPrincipal> introspectAsync(String token, CallContext context) {
    if (token == null || token.length() > 8192 || !token.matches("[A-Za-z0-9._~+/-]+=*"))
      return CompletableFuture.failedFuture(CurrentPrincipal.invalid());
    try {
      Instant deadline =
          clock.instant().plusMillis((long) (parameters.responseBudgetSeconds() * 1000));
      if (context != null && context.deadline() != null && context.deadline().isBefore(deadline))
        deadline = context.deadline();
      long budget = Duration.between(clock.instant(), deadline).toMillis();
      if (budget <= 0) throw CurrentPrincipal.unavailable();
      var instance = discovery.choose("identity-service");
      if (!settings.appEnv().equals("local") && !instance.scheme().equals("https"))
        throw CurrentPrincipal.unavailable();
      var builder =
          HttpRequest.newBuilder(instance.origin().resolve("/oauth2/introspect"))
              .timeout(Duration.ofMillis(budget))
              .header("Authorization", basic)
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("X-Request-Deadline", Long.toString(deadline.toEpochMilli()));
      if (context != null) {
        builder.header("X-Request-Id", context.requestId());
        if (context.traceparent() != null) builder.header("traceparent", context.traceparent());
      }
      var wire =
          client.sendAsync(
              builder.POST(HttpRequest.BodyPublishers.ofString("token=" + encode(token))).build(),
              HttpResponse.BodyHandlers.ofByteArray());
      var result =
          wire.thenApply(response -> decode(response))
              .orTimeout(budget, TimeUnit.MILLISECONDS)
              .exceptionally(
                  failure -> {
                    Throwable cause =
                        failure instanceof CompletionException ? failure.getCause() : failure;
                    if (cause instanceof OAuth2IntrospectionException known) throw known;
                    discovery.invalidate("identity-service", instance);
                    throw CurrentPrincipal.unavailable();
                  });
      result.whenComplete(
          (value, error) -> {
            if (error != null) wire.cancel(true);
          });
      return result;
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(
          failure instanceof OAuth2IntrospectionException known
              ? known
              : CurrentPrincipal.unavailable());
    }
  }

  @SuppressWarnings("unchecked")
  private CurrentPrincipal decode(HttpResponse<byte[]> response) {
    if (response.statusCode() != 200 || response.body().length > 1_048_576)
      throw CurrentPrincipal.unavailable();
    try {
      return CurrentPrincipal.parse(
          JSON.readValue(response.body(), Map.class),
          settings.issuer(),
          settings.audience(),
          clock);
    } catch (OAuth2IntrospectionException known) {
      throw known;
    } catch (RuntimeException malformed) {
      throw CurrentPrincipal.unavailable();
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    client.shutdownNow();
  }
}
