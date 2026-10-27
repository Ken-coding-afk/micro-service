-- T-11A 样例迁移 V2:兼容扩展(新增可空列 + 索引;旧列保留,可回退窗口内新旧版本共存)
ALTER TABLE t11a_greeting ADD COLUMN display_name TEXT;

CREATE INDEX idx_t11a_greeting_tenant ON t11a_greeting (tenant_id);
