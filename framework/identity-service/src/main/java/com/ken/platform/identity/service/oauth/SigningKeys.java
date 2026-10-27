package com.ken.platform.identity.service.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** 启动时读取持久密钥快照；轮换由秘密文件替换及受控重启完成，不在请求路径生成密钥。 */
public final class SigningKeys {
  private static final int MAX_BYTES = 64 * 1024;
  private static final Set<String> KEY_FIELDS =
      Set.of("kty", "kid", "use", "alg", "n", "e", "d", "p", "q", "dp", "dq", "qi");
  private final RSAKey active;
  private final JWKSet publicKeys;

  private SigningKeys(RSAKey active, JWKSet publicKeys) {
    this.active = active;
    this.publicKeys = publicKeys;
  }

  public static SigningKeys load(Path path) {
    try {
      if (!path.isAbsolute() || !Files.isRegularFile(path)) throw new IllegalArgumentException();
      byte[] bytes;
      try (var input = Files.newInputStream(path)) {
        bytes = input.readNBytes(MAX_BYTES + 1);
      }
      if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException();
      var mapper =
          JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
      var root = mapper.readTree(bytes);
      if (!root.isObject()
          || root.size() != 2
          || !root.path("active_kid").isString()
          || !root.path("keys").isArray()
          || root.path("keys").isEmpty()) throw new IllegalArgumentException();
      String activeId = root.path("active_kid").asString();
      var ids = new HashSet<String>();
      var published = new ArrayList<JWK>();
      RSAKey active = null;
      for (var node : root.path("keys")) {
        if (!node.isObject() || !KEY_FIELDS.containsAll(node.propertyNames()))
          throw new IllegalArgumentException();
        var key = JWK.parse(mapper.writeValueAsString(node));
        if (!(key instanceof RSAKey rsa)
            || !KeyUse.SIGNATURE.equals(key.getKeyUse())
            || !JWSAlgorithm.RS256.equals(key.getAlgorithm())
            || key.getKeyID() == null
            || !key.getKeyID().matches("[A-Za-z0-9_-]{1,128}")
            || !ids.add(key.getKeyID())
            || rsa.toRSAPublicKey().getModulus().bitLength() < 2048
            || rsa.toRSAPublicKey()
                    .getPublicExponent()
                    .compareTo(java.math.BigInteger.valueOf(65537))
                < 0) throw new IllegalArgumentException();
        if (activeId.equals(key.getKeyID())) {
          if (!rsa.isPrivate()) throw new IllegalArgumentException();
          var probe =
              new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload("key-pair-check"));
          probe.sign(new RSASSASigner(rsa));
          if (!probe.verify(new RSASSAVerifier(rsa.toRSAPublicKey())))
            throw new IllegalArgumentException();
          active = rsa;
        } else if (rsa.isPrivate()) {
          // 退役私钥不需要进入身份进程；只保留仍需验证的公钥。
          throw new IllegalArgumentException();
        }
        published.add(rsa.toPublicJWK());
      }
      if (active == null) throw new IllegalArgumentException();
      return new SigningKeys(active, new JWKSet(published));
    } catch (Exception failure) {
      // 解析器异常可能含私钥字段或文件路径，禁止附加原异常/原文。
      throw new IllegalArgumentException("身份签名密钥文件缺失、不可读或格式不合法");
    }
  }

  public JWKSource<SecurityContext> publicSource() {
    return new ImmutableJWKSet<>(publicKeys);
  }

  public JwtEncoder encoder() {
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(active)));
  }

  @Override
  public String toString() {
    return "SigningKeys[redacted]";
  }
}
