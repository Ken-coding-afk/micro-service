-- 角色说明是普通展示元数据；兼容已有行，不改变角色或授权版本。
ALTER TABLE sys_role ADD COLUMN description varchar(1024) NOT NULL DEFAULT '';
