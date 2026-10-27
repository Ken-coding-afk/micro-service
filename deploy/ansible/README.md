# 项目如何使用发布入口

这些入口复用 Compose、Ansible、Flyway、Alembic、OpenSSL 和 Certbot。没有发布控制台、常驻发布服务或任务数据库。旧布局有语法、校验、Nginx TLS 与恢复测试证据；本次目录和交付流程仍在定向验证，Docker 验收暂停，尚无两台真实 Linux 主机验收，不能承诺零停机。

## 1. 项目交付给运维的材料

源码项目先按生成项目 README 接入，按项目 README 的 build.py/package.py 两阶段命令构建镜像和部署包。本文件在框架中位于 `deploy/ansible/`，打包后位于部署包 `deploy/ansible/`。下述执行目录均为部署包根，不是业务源码根。构建阶段产出固定版本的 identity/Gateway/业务镜像，推入项目获准的制品库并取得 `repository@sha256:...`；运行主机只拉取镜像，不编译源码。

每份部署包含项目自己的 config、infra、deploy、业务迁移和权限清单，以及本发行运维工具。部署前通过现有 CI 的制品传输/SSH 文件传输放到管理机 `project_source` 和目标机 `project_root`；不要打包 `.local`、`.venv`、`.git` 或秘密。当前入口不实现另一个制品分发平台。目标目录中的文件必须与本次 `deploy/release.yaml` SHA-256 清单一致；不能把更新工作树视为发布成功。

源码项目在构建前从 `deploy/release.yaml.example` 准备发布策略，连同全部构建输入提交；完整构建、上传镜像后，将 digest 映射保存在项目 `.local/`，再用同次构建记录执行 `uv run --frozen python scripts/package.py --build-record <构建记录> --images <digest映射> --output <新目录>`。部署包的 `deploy/release.yaml` 由打包器生成，已包含准确镜像、Compose、迁移、文件摘要和源码版本，不再从示例重写一份，也不手工改 files。要改变输入，应回到源码项目提交、重建并重新打包。环境文件与秘密单独受控提供。

本轮本地候选镜像尚未上传制品库，不能在服务器上拉取 `local/...`。正式发布前必须完成制品上传授权与真实环境验收。

## 2. 管理机与目标机

管理机用 Linux/WSL；Windows 本地开发继续用原 Compose/uv 命令。管理机安装 `ansible-core==2.19.3`，从本目录 requirements.yaml 安装固定 community.docker；目标机准备 Docker Compose v2（建议不低于 2.32）、Python 3.12、OpenSSL、SSH 与项目文件权限。每台执行工具的主机从部署包根运行 `uv sync --frozen`，inventory 的 tools_python / ansible_python_interpreter 指向该环境。运行账号是否具有 Docker 权限由运维配置，脚本不自动提权。

复制 `config/environments/test/hosts.yaml.example` 为项目的 hosts.yaml，生产使用独立目录和输入；将示例私网 IP、路径、域名替换为实际值。release_controller、resource_controller、migration_controller 各只有一个权威执行点，可以是同一台管理机；apps 按清单顺序逐主机处理。infra_services、app_services 明确指定，不从仓库里猜测业务。

每台主机都有自己的环境文件与运行目录。identity-service.env、api-gateway.env、各业务 env 只引用自己的运行秘密；初始化/迁移用单独管理身份。`environment_file` 供资源/配置/迁移工具使用，`compose_env_file` 供 Compose 插值，不能把管理员环境整体注入应用容器。

跨主机连接使用私网 IP/DNS 和映射端口。`INFRA_BIND_HOST` 指向该组件主机的私网地址，不能沿用 local 的 127.0.0.1；生产防火墙只放行应用与管理网段。各服务 Nacos 发布地址必须是别的主机能访问的地址。每台主机的 bridge network 仅用于该主机容器；入口 upstream 使用固定 Gateway 私网 IP:端口。

入口使用 `deploy/compose.yaml` 加 `deploy/compose.server.yaml`。compose.env 中设置 ENTRY_BIND_HOST、ENTRY_PORT=80、HTTPS_PORT=443、APP_BIND_HOST、EDGE_CONFIG_FILE（`project_root/.local/edge/default.conf`）、EDGE_CERTIFICATE_DIR、WEB_ROOT、RUNTIME_DIR、镜像与项目环境。框架会核对网络归属后创建缺失的本机网络；已有同名无归属标签的网络停止核对，不能自动改名或重建卷。

内部身份 HTTPS、私有 CA 信任、组件 TLS 与防火墙仍必须按项目实际网络配置，不能将示例私网 HTTP 当作全部生产安全配置。当前未交付这些实际服务器配置的验收证据。

## 3. 首次部署顺序

工作目录为管理机部署包根，先核实备份责任与实际备份引用，设置唯一 operation_id。

```bash
ansible-galaxy collection install -r deploy/ansible/requirements.yaml
ansible-playbook -i config/environments/test/hosts.yaml deploy/ansible/infra.yaml -e operation_id=sample-001-infra
ansible-playbook -i config/environments/test/hosts.yaml deploy/ansible/initialize.yaml -e operation_id=sample-001-initialize
ansible-playbook -i config/environments/test/hosts.yaml deploy/ansible/migrate.yaml -e operation_id=sample-001-migrate
ansible-playbook -i config/environments/test/hosts.yaml deploy/ansible/deploy.yaml -e operation_id=sample-001-deploy
```

infra 需要显式 infrastructure_maintenance_approved，仅启动清单选中的 self_hosted 组件；全部 external 时不需要 infra play。initialize 使用原 create/verify 资源入口及 Nacos 发布器的摘要冲突保护。migrate 先在所有 apps 主机预拉取应用镜像，并在唯一迁移机预拉取迁移镜像，再逐库执行，全部成功才生成带本发行摘要的管理机凭据；deploy 遇到迁移未完成或摘要变化就停止。

第一次部署的 app_services 应包含本次业务服务；identity_controller 唯一且属于 apps。配置 service_provisioning=true，首次身份主机 bootstrap_identity=true，后续改 false。把包内 config/bootstrap/services.json.example 放到身份维护机 RUNTIME_DIR/identity-bootstrap-secrets/services.json；按文件列出的路径准备每用途独立的 32～64 位 URL-safe 随机秘密，文件 0600，不进 Git。把对应资源内省秘密放到该服务运行秘密目录，把 Gateway 内省与 web 秘密放到 Gateway 运行秘密目录；这些运行绑定沿用组件运行环境样例，不注入维护数据库账号。服务名对应的权限发布 client_id/manifest 已在 config/bootstrap/permission-publications.yaml.example 生成；修正宿主 secret_file 路径后纳入包外变量输入，秘密只放在身份维护机。首次签名/管理员 bootstrap 使用原独立文件，services.json 不替代它。

部署先统一完成技术登记→身份 readiness→权限目录发布→应用客户端登记，再逐主机启动业务和入口。现有策略保留，不自动创建业务租户、用户、角色或扩大授权；管理员随后完成业务授权。缺失秘密或客户端冲突停止，不生成替代密码。生产无 Swagger 与演示账号。

破坏性 SQL 在源码发布策略中声明 migration_mode=maintenance。执行 migrate 前必须人工停止全部相关入口、旧应用、计划任务及消费者，核对没有写入者后通过包外变量提交 maintenance_writers_stopped=true 和 maintenance_evidence；默认拒绝执行，不能只设置 maintenance_window。兼容变更仍需明确备份和旧版本兼容。更换迁移机时在 migration_previous_states 中按 target 指向受控成功归档，不能以空 .local 免除历史校验。

当前应用更新要求安排维护窗口：先拉取所选镜像，再优雅停止和更新选定实例，等待本机 readiness 后进入下一台；全部所选实例就绪后才由管理机统一执行有效 TLS 的入口烟测。已有数据卷不删除，未选应用不更新。多副本可降低停机范围，但网关排空、服务注销传播、非幂等请求与负载均衡行为仍须真实多机验收，不能把 serial=1 当作无损滚动发布证明。

## 4. TLS 与证书维护

provided 模式：证书责任方将有效 fullchain.pem / privkey.pem 放入 `EDGE_CERTIFICATE_DIR/versions/<version>/`，私钥仅运维可读；首次创建 current 指向该版本目录。Ansible 校验证书域名、有效期（至少 7 天）、私钥匹配并测试候选 Nginx 配置，之后才更新入口。管理机烟测验证对外证书信任链。私有 CA 必须加入管理机和调用服务信任库，禁止关闭证书校验。

ACME 模式使用成熟 Certbot。首次尚无证书时，以 server Compose 的 certificate-bootstrap profile 单独启动 acme-bootstrap，仅服务 HTTP challenge 路径；宿主 Certbot `certonly --webroot` 使用同一个 ACME_WEBROOT。签发成功后保存版本目录/current，停止 acme-bootstrap，再启动正式 TLS 入口。具体公网 DNS、80 端口和 ACME 账号由运维准备；本地夹具未验证真实签发。

正常续期沿用宿主 Certbot timer，首次配置执行 `certbot renew --dry-run`。deploy hook 调用 `scripts/deploy/activate_certificate.py`，参数固定为本项目 project-root、compose-env、compose-file、certificate-dir、fullchain、privatekey、host。它先校验候选，版本化保留，原子切换 current，然后 `nginx -t` / reload；失败恢复旧引用并保留操作锁，不能静默继续。provided 更新使用同一入口。它不安装定时器、不申请证书、不代替告警接收方；项目需监控低于 30 天提醒、低于 7 天升级告警，并核验对外新证书。

## 5. 失败、回退和中断核对

管理机与目标机都使用原子目录锁。失败时不自动删锁、不重试未知写入、不继续其他主机。先运行 reconcile.yaml 查看容器、版本与锁，再核对 `.local/operations`、迁移状态文件、PG 历史和尚存进程；确认旧任务已结束后，由运维移走对应锁并使用新操作 ID 恢复。迁移的 unknown 状态仍需原迁移工具显式核对，Ansible 不自动添加 confirm-unknown。

每份 release.yaml 必须填写正整数 rollback_window_hours、compatible_previous_versions（首次为空）、owner 和 approval_reference。全部主机烟测成功后才在管理机保存 `<operation_id>-deployment.json`，记录 UTC 成功时间、rollback_deadline、contract_not_before、镜像和清单摘要，并原子更新该项目/环境的 latest-deployment.json。失败留锁且不更新成功记录；窗口从实际成功时起算，示例 24 小时不是全项目默认值。

回退使用已验证的旧镜像/配置和独立回退清单：compatible_schema_from 必须列明当前发行对应的 schema 兼容范围，migrations 为空，rollback_from 与管理机当前成功记录匹配，且目标发行 ID 在当前记录的 compatible_previous_versions 中、窗口尚未过期。明确核对授权行为，使用新 operation_id 和 `-e rollback_from=<当前发行ID> -e rollback_authorization_reviewed=true` 运行 rollback.yaml。校验发生在管理锁内，超期停止，不自动延期；它复用受控应用更新，不执行数据库 downgrade。曾包含退役委派能力的旧身份镜像不能视为等价安全回退。

操作记录保存 operation_id、发行号、主机与状态；部署成功另保存上述回退凭据，不保存秘密，秘密相关命令 no_log。记录归档由项目已有日志/制品系统承担。真实双机停机、管理机断连、跨机网络分区和生产备份恢复仍在计划未验证项中。

## 6. 部分发布失败的恢复操作

部署在实例变更前保存 operation-attempt.json（targets、schema_release、成功基线、期限），latest-attempt.json 指向本次尝试。主机 .local/operations 下每个服务的开始记录保存旧镜像和入口配置备份路径，ready 记录只在真正就绪后写入。成功记录仅包含本次选中镜像，并按主机累计 deployments；失败不覆盖 latest-deployment.json。

reconcile 后确认旧任务退出、范围和迁移状态，归档故障锁，再使用新的 operation_id，并传入 `-e recovery_operation=<失败操作ID> -e recovery_reviewed=true`。这是显式恢复确认，不会在脚本里自动解锁。inventory 必须覆盖前次可能修改的全部主机/服务，不使用 --limit 跳过控制阶段。

若选择恢复上一成功应用，准备对应旧镜像/配置的回退清单，migrations 为空，兼容声明包含实际 schema 来源；再传 `-e rollback_from=<实际schema发行ID> -e rollback_authorization_reviewed=true` 调用 rollback.yaml。工具检查原成功实例镜像、范围与原恢复期限；回退再次失败也不自动延期，不改变数据库。新增服务无旧实例基线时拒绝自动恢复，需先人工明确如何移除/保留；首次安装无基线时修正问题后显式续发。

Nginx 应用发布采用维护窗口：候选校验后 copy 备份旧 default.conf，再重建容器加载新文件。运行烟测失败时，读取该操作 nginx.json 的 previous_config 和 previous_images，核对仍有效的证书，恢复记录指向的旧配置文件到 EDGE_CONFIG_FILE，将 NGINX_IMAGE 恢复为兼容旧摘要，再用同一 Compose 文件/环境执行 `up -d --no-deps --force-recreate nginx`。重测 nginx -t 与公开 HTTPS，保留故障证据后按正式恢复入口记录最终状态。禁止将手动容器恢复直接伪记为全批次成功；这里不承诺配置自动回退或无损发布。证书续期工具自己的 current 引用回退是另一条流程。
