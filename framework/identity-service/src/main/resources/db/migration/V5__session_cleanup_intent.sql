-- 本服务控制侧 Redis 清理 Outbox：撤销事实与待清理标记同一行/同一事务。
ALTER TABLE sys_login_session ADD COLUMN cleanup_pending boolean NOT NULL DEFAULT false;
ALTER TABLE sys_login_session ADD CONSTRAINT sys_login_session_cleanup_valid
    CHECK(NOT cleanup_pending OR (revoked_at IS NOT NULL AND redis_session_id IS NOT NULL));
CREATE INDEX sys_login_session_pending_cleanup ON sys_login_session(revoked_at,id) WHERE cleanup_pending;
