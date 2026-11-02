-- 密码最少字符数基线从 15 降至 8（NIST SP 800-63B 最低基线，配合既有弱密码黑名单）。
-- 权威合同见 docs/design/05-认证与OAuth2设计.md 安全策略字段表；历史迁移 V2 不追改。
ALTER TABLE sys_security_policy DROP CONSTRAINT sys_security_policy_password_min_chars_check;
ALTER TABLE sys_security_policy ADD CONSTRAINT sys_security_policy_password_min_chars_check
    CHECK (password_min_chars BETWEEN 8 AND 32);
-- 平台生效值随新基线调整，存量租户历史行（满足旧约束即 >=15）在放宽后仍合法。
UPDATE sys_security_policy SET password_min_chars = 8, updated_at = now()
WHERE owner_type = 'PLATFORM' AND password_min_chars = 15;
