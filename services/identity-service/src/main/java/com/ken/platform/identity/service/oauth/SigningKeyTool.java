package com.ken.platform.identity.service.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 独立运维入口；不启动 Spring/数据库，不覆盖已有文件，不向终端输出密钥。 */
public final class SigningKeyTool {
  private SigningKeyTool() {}

  public static void create(Path output, Path previous) throws Exception {
    if (!output.isAbsolute() || !Files.isDirectory(output.getParent()) || Files.exists(output))
      throw new IllegalArgumentException("输出必须是受控目录下尚不存在的绝对文件路径");
    var keys = new ArrayList<JWK>();
    if (previous != null) {
      keys.addAll(
          SigningKeys.load(previous)
              .publicSource()
              .get(new JWKSelector(new JWKMatcher.Builder().build()), null));
    }
    var active =
        new RSAKeyGenerator(3072)
            .keyID(UUID.randomUUID().toString())
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .generate();
    keys.add(active);
    byte[] bytes =
        new JWKSet(keys, Map.of("active_kid", active.getKeyID()))
            .toString(false)
            .getBytes(StandardCharsets.UTF_8);
    var options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    // POSIX 创建时即 0600；Windows 继承已受控父目录 ACL，不先写入再缩权限。
    var attributes =
        Files.getFileAttributeView(output.getParent(), PosixFileAttributeView.class) == null
            ? new java.nio.file.attribute.FileAttribute<?>[0]
            : new java.nio.file.attribute.FileAttribute<?>[] {
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            };
    try (var channel = FileChannel.open(output, options, attributes)) {
      var buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    }
  }

  public static void main(String[] args) {
    try {
      if (args.length == 2 && args[0].equals("create")) create(Path.of(args[1]), null);
      else if (args.length == 3 && args[0].equals("rotate"))
        create(Path.of(args[2]), Path.of(args[1]));
      else throw new IllegalArgumentException();
      System.out.println("签名密钥文件已创建；请受控交付并重启身份服务");
    } catch (Exception failure) {
      System.err.println("签名密钥操作失败；检查命令、受控目录、源文件和输出是否已存在");
      System.exit(2);
    }
  }
}
