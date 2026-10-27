-- T-11A 样例迁移 V3:故意损坏的迁移(引用不存在的表,执行期失败)。
-- 用于验证:DDL 失败立即停止,状态 failed,后续步骤不执行。
CREATE TABLE t11a_oops (
    id  BIGINT PRIMARY KEY,
    ref BIGINT NOT NULL REFERENCES t11a_missing_table (id)
);
