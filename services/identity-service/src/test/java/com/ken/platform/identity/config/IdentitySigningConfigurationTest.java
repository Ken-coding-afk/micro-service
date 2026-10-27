package com.ken.platform.identity.config;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.identity.service.oauth.SigningKeyTool;
import com.ken.platform.identity.service.oauth.SigningKeys;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import tools.jackson.databind.json.JsonMapper;

class IdentitySigningConfigurationTest {
  private static RSAKey first;
  private static RSAKey second;
  @TempDir Path directory;

  @BeforeAll
  static void keys() throws Exception {
    first =
        new RSAKeyGenerator(2048)
            .keyID("first")
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .generate();
    second =
        new RSAKeyGenerator(2048)
            .keyID("second")
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .generate();
  }

  private Path write(String active, List<JWK> keys) throws Exception {
    Path path = directory.resolve("signing.json");
    Files.writeString(path, new JWKSet(keys, Map.of("active_kid", active)).toString(false));
    return path;
  }

  private String token(JwtEncoder encoder) {
    Instant now = Instant.now();
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder()
                    .issuer("https://identity.example")
                    .subject("fixture-user")
                    .audience(List.of("fixture-client"))
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(300))
                    .build()))
        .getTokenValue();
  }

  @Test
  void restartKeepsIdentityAndPublishesOnlyPublicMaterial() throws Exception {
    Path path = write("first", List.of(first));
    byte[] original = Files.readAllBytes(path);
    var before = SigningKeys.load(path);
    String signed = token(before.encoder());
    var after = SigningKeys.load(path);
    var config = new IdentitySigningConfiguration();
    assertThat(config.jwtDecoder(after.publicSource()).decode(signed).getSubject())
        .isEqualTo("fixture-user");
    assertThat(config.jwtDecoder(after.publicSource()).decode(token(after.encoder())).getHeaders())
        .containsEntry("kid", "first");
    var published =
        after.publicSource().get(new JWKSelector(new JWKMatcher.Builder().build()), null);
    assertThat(published).hasSize(1).allMatch(key -> !key.isPrivate());
    assertThat(published.getFirst().toJSONObject())
        .doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
    assertThat(Files.readAllBytes(path)).isEqualTo(original);
    assertThat(after.toString()).isEqualTo("SigningKeys[redacted]");
  }

  @Test
  void rotationSignsOnlyActiveKeyWhileOldTokenStillVerifies() throws Exception {
    String old = token(SigningKeys.load(write("first", List.of(first))).encoder());
    // 旧 key 故意排第一，不能按集合顺序选择签名者。
    var rotated = SigningKeys.load(write("second", List.of(first.toPublicJWK(), second)));
    var decoder = new IdentitySigningConfiguration().jwtDecoder(rotated.publicSource());
    assertThat(decoder.decode(old).getHeaders()).containsEntry("kid", "first");
    assertThat(decoder.decode(token(rotated.encoder())).getHeaders())
        .containsEntry("kid", "second");
    assertThat(rotated.publicSource().get(new JWKSelector(new JWKMatcher.Builder().build()), null))
        .hasSize(2)
        .allMatch(key -> !key.isPrivate());
    assertThatThrownBy(() -> token(new NimbusJwtEncoder(rotated.publicSource())))
        .isInstanceOf(org.springframework.security.oauth2.jwt.JwtEncodingException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "absent-active",
        "public-active",
        "retired-private",
        "duplicate-id",
        "wrong-alg",
        "wrong-use",
        "private-mismatch",
        "unknown-field",
        "remote-key",
        "duplicate-json",
        "oversize",
        "empty"
      })
  void malformedSecretsFailClosedWithoutEchoOrCause(String scenario) throws Exception {
    Path path = write("first", List.of(first));
    var key = new LinkedHashMap<String, Object>(first.toJSONObject());
    var keys = new ArrayList<Map<String, Object>>();
    keys.add(key);
    String active = "first";
    switch (scenario) {
      case "absent-active" -> active = "missing";
      case "public-active" -> {
        keys.clear();
        keys.add(first.toPublicJWK().toJSONObject());
      }
      case "retired-private" -> keys.add(second.toJSONObject());
      case "duplicate-id" -> keys.add(first.toPublicJWK().toJSONObject());
      case "wrong-alg" -> key.put("alg", "RS512");
      case "wrong-use" -> key.put("use", "enc");
      case "private-mismatch" -> {
        key.put("n", second.getModulus().toString());
        key.put("e", second.getPublicExponent().toString());
      }
      case "unknown-field" -> key.put("private_hint", "DO_NOT_ECHO_SECRET");
      case "remote-key" -> key.put("x5u", "https://untrusted.example/key");
      default -> {}
    }
    Files.writeString(
        path,
        JsonMapper.builder()
            .build()
            .writeValueAsString(Map.of("active_kid", active, "keys", keys)));
    if (scenario.equals("duplicate-json"))
      Files.writeString(
          path, "{\"active_kid\":\"first\",\"active_kid\":\"DO_NOT_ECHO_SECRET\",\"keys\":[]}");
    if (scenario.equals("oversize")) Files.writeString(path, "DO_NOT_ECHO_SECRET".repeat(5000));
    if (scenario.equals("empty")) Files.writeString(path, "");
    var failure = catchThrowable(() -> SigningKeys.load(path));
    assertThat(failure)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("身份签名密钥文件缺失、不可读或格式不合法")
        .hasNoCause();
    assertThat(failure.toString())
        .doesNotContain(
            "DO_NOT_ECHO_SECRET", first.getPrivateExponent().toString(), path.toString());
  }

  @Test
  void missingRelativeOrWeakKeysNeverCreateAnEphemeralReplacement() throws Exception {
    Path missing = directory.resolve("not-created.json");
    assertThatThrownBy(() -> SigningKeys.load(missing))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Files.exists(missing)).isFalse();
    assertThatThrownBy(() -> SigningKeys.load(Path.of("relative-key.json")))
        .isInstanceOf(IllegalArgumentException.class);
    var generator = java.security.KeyPairGenerator.getInstance("RSA");
    generator.initialize(1024);
    var pair = generator.generateKeyPair();
    var weak =
        new RSAKey.Builder((java.security.interfaces.RSAPublicKey) pair.getPublic())
            .privateKey(pair.getPrivate())
            .keyID("weak")
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .build();
    Path weakPath = write("weak", List.of(weak));
    assertThatThrownBy(() -> SigningKeys.load(weakPath))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new IdentitySigningConfiguration().identitySigningKeys(new MockEnvironment()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("IDENTITY_SIGNING_JWK_SECRET_FILE");
  }

  @Test
  void offlineToolCreatesRotatedFileWithoutOverwritingOrKeepingRetiredPrivateKeys()
      throws Exception {
    Path original = directory.resolve("original.json");
    SigningKeyTool.create(original, null);
    byte[] originalBytes = Files.readAllBytes(original);
    var originalKeys = SigningKeys.load(original);
    var oldPublic =
        originalKeys
            .publicSource()
            .get(new JWKSelector(new JWKMatcher.Builder().build()), null)
            .getFirst();
    assertThat(((RSAKey) oldPublic).toRSAPublicKey().getModulus().bitLength()).isEqualTo(3072);
    String signed = token(originalKeys.encoder());
    Path rotated = directory.resolve("rotated.json");
    SigningKeyTool.create(rotated, original);
    var rotatedKeys = SigningKeys.load(rotated);
    var sourceKeys = JWKSet.parse(Files.readString(rotated)).getKeys();
    assertThat(sourceKeys).hasSize(2);
    assertThat(sourceKeys.stream().filter(JWK::isPrivate).count()).isEqualTo(1);
    assertThat(
            sourceKeys.stream()
                .filter(key -> key.getKeyID().equals(oldPublic.getKeyID()))
                .findFirst()
                .orElseThrow()
                .isPrivate())
        .isFalse();
    assertThat(
            new IdentitySigningConfiguration()
                .jwtDecoder(rotatedKeys.publicSource())
                .decode(signed)
                .getSubject())
        .isEqualTo("fixture-user");
    assertThat(Files.readAllBytes(original)).isEqualTo(originalBytes);
    assertThatThrownBy(() -> SigningKeyTool.create(original, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Files.readAllBytes(original)).isEqualTo(originalBytes);
    Path noOutput = directory.resolve("no-output.json");
    assertThatThrownBy(() -> SigningKeyTool.create(noOutput, directory.resolve("missing.json")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Files.exists(noOutput)).isFalse();
    if (Files.getFileAttributeView(original, java.nio.file.attribute.PosixFileAttributeView.class)
        != null) {
      assertThat(Files.getPosixFilePermissions(original))
          .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
  }
}
