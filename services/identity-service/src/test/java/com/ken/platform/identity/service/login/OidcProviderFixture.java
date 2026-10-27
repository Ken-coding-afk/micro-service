package com.ken.platform.identity.service.login;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import tools.jackson.databind.json.JsonMapper;

/** 仅本地协议夹具：真实 HTTP、一次性 code、PKCE、签名 ID token；不冒充外部厂商验收。 */
final class OidcProviderFixture implements AutoCloseable {
  private final HttpServer server;
  private final RSAKey key;
  private final Map<String, Map<String, String>> codes = new ConcurrentHashMap<>();
  String defect = "";

  OidcProviderFixture() throws Exception {
    key = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("external-fixture").generate();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/authorize",
        exchange -> {
          var parameters = parse(exchange.getRequestURI().getRawQuery());
          String code = UUID.randomUUID().toString();
          codes.put(code, parameters);
          exchange
              .getResponseHeaders()
              .set(
                  "Location",
                  parameters.get("redirect_uri")
                      + "?code="
                      + code
                      + "&state="
                      + parameters.get("state"));
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/jwks", exchange -> respond(exchange, new JWKSet(key.toPublicJWK()).toString()));
    server.createContext(
        "/userinfo",
        exchange ->
            respond(
                exchange,
                "{\"sub\":\"external-subject\",\"email\":\"same@example.com\",\"email_verified\":true}"));
    server.createContext(
        "/token",
        exchange -> {
          try {
            var request =
                parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var intent = codes.remove(request.get("code"));
            if (intent == null) throw new IllegalArgumentException("used code");
            var digest =
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(request.get("code_verifier").getBytes(StandardCharsets.US_ASCII));
            if (!Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest)
                .equals(intent.get("code_challenge")))
              throw new IllegalArgumentException("PKCE rejected");
            var now = Instant.now();
            var claims =
                new JWTClaimsSet.Builder()
                    .issuer(defect.equals("issuer") ? "https://invalid.example" : origin())
                    .subject("external-subject")
                    .audience(defect.equals("audience") ? "wrong" : "fixture-client")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(120)))
                    .claim("nonce", defect.equals("nonce") ? "wrong" : intent.get("nonce"))
                    .build();
            var token =
                new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims);
            token.sign(
                new RSASSASigner(
                    defect.equals("signature")
                        ? new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate()
                        : key));
            respond(
                exchange,
                JsonMapper.builder()
                    .build()
                    .writeValueAsString(
                        Map.of(
                            "access_token",
                            "fixture-external-token",
                            "token_type",
                            "Bearer",
                            "expires_in",
                            120,
                            "scope",
                            "openid profile email",
                            "id_token",
                            token.serialize())));
          } catch (Exception rejected) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
          }
        });
    server.start();
  }

  String origin() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  ClientRegistration registration() {
    return ClientRegistration.withRegistrationId("fixture")
        .clientId("fixture-client")
        .clientSecret("fixture-client-secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("http://localhost:18080/login/oauth2/code/fixture")
        .scope("openid", "profile", "email")
        .authorizationUri(origin() + "/authorize")
        .tokenUri(origin() + "/token")
        .jwkSetUri(origin() + "/jwks")
        .issuerUri(origin())
        .userInfoUri(origin() + "/userinfo")
        .userNameAttributeName("sub")
        .build();
  }

  static Map<String, String> parse(String query) {
    var values = new HashMap<String, String>();
    for (String part : query.split("&")) {
      var pair = part.split("=", 2);
      values.put(
          URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
          pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
    }
    return values;
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws java.io.IOException {
    byte[] raw = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, raw.length);
    exchange.getResponseBody().write(raw);
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
