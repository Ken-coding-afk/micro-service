package com.ken.platform.identity.service.login;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.service.security.SecurityPolicies.Effective;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Redis 原子滑动窗口/固定锁期；随机操作 ID 令断连重放不重复计数或再次清除新失败。 */
public final class LoginProtection {
  record Ticket(List<String> keys, String nonce) {}

  public static final class Limited extends PlatformException {
    private final long retryAfterSeconds;

    Limited(long seconds) {
      super("LOGIN_RATE_LIMITED", "登录请求暂时受限", 429);
      retryAfterSeconds = seconds;
    }

    public long retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }

  private static final String LUA =
      """
      local t=redis.call('TIME')
      local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
      local mode=ARGV[1]
      local phase=mode=='begin' and 'begin' or 'finish'
      local remembered=redis.call('HGET',KEYS[4],phase)
      if remembered then
        local status,untilAt=string.match(remembered,'(%d+):(%d+)')
        return {tonumber(status),tonumber(untilAt),now}
      end
      local function result(status,untilAt)
        redis.call('HSET',KEYS[4],phase,tostring(status)..':'..tostring(untilAt))
        redis.call('PEXPIRE',KEYS[4],300000)
        return {status,untilAt,now}
      end
      local threshold=tonumber(ARGV[3])
      local window=tonumber(ARGV[4])*1000
      local lockMs=tonumber(ARGV[5])*1000
      if mode=='begin' then
        redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-300000)
        local ipWindow=tonumber(ARGV[7])*1000
        local count=redis.call('ZCOUNT',KEYS[1],'('..tostring(now-ipWindow),'+inf')
        if count>=tonumber(ARGV[6]) then
          local oldest=redis.call('ZRANGEBYSCORE',KEYS[1],'('..tostring(now-ipWindow),'+inf','WITHSCORES','LIMIT',0,1)
          return result(2,tonumber(oldest[2])+ipWindow)
        end
        redis.call('ZADD',KEYS[1],'NX',now,ARGV[2])
        redis.call('PEXPIRE',KEYS[1],300000)
      end
      local lockedUntil=tonumber(redis.call('HGET',KEYS[3],'until') or '0')
      if lockedUntil>now then return result(1,lockedUntil) end
      if lockedUntil>0 then
        redis.call('DEL',KEYS[2],KEYS[3])
      end
      redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',now-3600000)
      if mode=='success' then
        redis.call('DEL',KEYS[2])
        return result(0,0)
      end
      if mode=='failure' then
        redis.call('ZADD',KEYS[2],'NX',now,ARGV[2])
        redis.call('PEXPIRE',KEYS[2],3600000)
      end
      if redis.call('ZCOUNT',KEYS[2],'('..tostring(now-window),'+inf')>=threshold then
        local untilAt=now+lockMs
        redis.call('HSET',KEYS[3],'until',untilAt)
        -- 保留锁到期墓碑，下一次请求清除该轮失败；窗口加长不丢仍有效的历史。
        redis.call('PEXPIRE',KEYS[3],7200000)
        return result(1,untilAt)
      end
      return result(mode=='failure' and 3 or 0,0)
      """;
  private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>(LUA, List.class);
  private final StringRedisTemplate redis;
  private final String prefix;

  public LoginProtection(StringRedisTemplate redis, String project, String environment) {
    if (project == null
        || !project.matches("[a-z][a-z0-9-]{0,63}")
        || !java.util.Set.of("local", "test", "prod")
            .contains(environment == null ? "" : environment)) {
      throw new IllegalArgumentException("登录保护缺少合法项目/环境绑定");
    }
    this.redis = redis;
    // 显式登记的全局安全用途；同一 hash slot 让 IP 和账号检查原子完成。
    prefix = project + ":" + environment + ":identity-service:global:{login}:";
  }

  Ticket begin(String account, InetAddress source, Effective policy) {
    if (source == null) throw new PlatformException("LOGIN_SOURCE_UNAVAILABLE", "无法核实登录来源", 503);
    String digest = digest(account);
    String nonce = UUID.randomUUID().toString();
    var ticket =
        new Ticket(
            List.of(
                prefix + "ip:" + digest(source.getHostAddress()),
                prefix + "failures:" + digest,
                prefix + "lock:" + digest,
                prefix + "operation:" + digest + ":" + nonce),
            nonce);
    execute("begin", ticket, policy);
    return ticket;
  }

  void failure(Ticket ticket, Effective policy) {
    execute("failure", ticket, policy);
  }

  void success(Ticket ticket, Effective policy) {
    execute("success", ticket, policy);
  }

  private void execute(String mode, Ticket ticket, Effective policy) {
    List<?> result;
    try {
      result =
          redis.execute(
              SCRIPT,
              ticket.keys(),
              mode,
              ticket.nonce(),
              Integer.toString(policy.loginFailureThreshold()),
              Integer.toString(policy.loginFailureWindowSeconds()),
              Integer.toString(policy.loginLockSeconds()),
              Integer.toString(policy.loginIpAttemptLimit()),
              Integer.toString(policy.loginIpWindowSeconds()));
      if (result == null
          || result.size() != 3
          || result.stream().anyMatch(value -> !(value instanceof Number))) {
        throw new IllegalStateException("无效登录保护结果");
      }
    } catch (RuntimeException unavailable) {
      throw new PlatformException("LOGIN_PROTECTION_UNAVAILABLE", "登录保护暂不可用", 503);
    }
    long status = ((Number) result.get(0)).longValue();
    if (status == 1 || status == 2) {
      long remaining = ((Number) result.get(1)).longValue() - ((Number) result.get(2)).longValue();
      throw new Limited(Math.max(1, (remaining + 999) / 1000));
    }
    if (status != 0 && status != 3)
      throw new PlatformException("LOGIN_PROTECTION_UNAVAILABLE", "登录保护暂不可用", 503);
  }

  static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
    }
  }
}
