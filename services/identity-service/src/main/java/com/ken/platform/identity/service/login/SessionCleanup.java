package com.ken.platform.identity.service.login;

import com.ken.platform.foundation.http.PlatformException;
import com.ken.platform.identity.dal.mapper.login.LoginStore;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 清理只消费已撤销绑定的持久意图；多实例重复删除同一随机会话 ID 是幂等操作。 */
@Service
public final class SessionCleanup {
  private final LoginStore store;
  private final SessionRepository<?> sessions;
  private final TransactionTemplate transactions;

  public SessionCleanup(
      LoginStore store, SessionRepository<?> sessions, PlatformTransactionManager manager) {
    this.store = store;
    this.sessions = sessions;
    transactions = new TransactionTemplate(manager);
    transactions.setTimeout(5);
  }

  public void clean(UUID id) {
    var binding = store.session(id, false);
    if (binding == null
        || binding.revokedAt() == null
        || !binding.cleanupPending()
        || binding.redisSessionId() == null) return;
    try {
      sessions.deleteById(binding.redisSessionId());
      transactions.execute(
          status -> {
            store.cleanupCompleted(id, binding.redisSessionId());
            return null;
          });
    } catch (RuntimeException unavailable) {
      throw new PlatformException("SESSION_CLEANUP_UNAVAILABLE", "会话清理暂不可用", 503);
    }
  }

  @Scheduled(fixedDelay = 10000, initialDelay = 10000)
  public void retryPending() {
    try {
      for (UUID id : store.pendingCleanup()) clean(id);
    } catch (RuntimeException unavailable) {
      org.slf4j.LoggerFactory.getLogger(SessionCleanup.class)
          .warn("identity.session.cleanup.unavailable");
    }
  }
}
