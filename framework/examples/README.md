# Java 与 Python 混合项目接入

本目录是一个混合项目：services 同时包含 Java 和 Python 服务，两者各自保存资源，使用统一账号、权限、租户事务与发现调用。没有聊天、算法任务或 Agent 运行时。基础服务镜像不包含这里的业务代码。

当前候选使用 Java 1.0.0-rc.3 / Python 1.0.0rc3。基础服务镜像与 SDK 按发行清单锁定；本地验证与真实环境未验证项分别记录在精简实施计划。

## 1. 新项目取得什么

框架发行目录包含：services（两个服务）、web、config、infra、deploy、examples、.gitignore、根 pyproject.toml/uv.lock、Maven wrapper、artifacts/java、artifacts/python、artifacts/identity-migrations 与 release-manifest.json、framework-baseline.json。框架父 POM/基础库和 Python SDK 通过制品接入；不复制 foundation、identity-service、api-gateway、python-sdk 源码。

先在新目录展开整个发行包，按下面步骤跑通一个混合项目。框架源码中的 examples/services 和 examples/web 已分别映射为发行项目根的 services 和 web，不需要重新复制。两个服务共用本项目的身份服务、网关、环境及发布材料。跑通后把服务改为自己的领域名称，同步启动声明、权限清单、资源、环境绑定、Nacos 文件/路由；不同项目不共享身份库。

```text
新项目/
├── services/
│   ├── sample-java-service/
│   └── sample-python-service/
├── web/
├── config/ + infra/ + deploy/
├── examples/                 # 运行说明、Compose 覆盖和验证工具
├── artifacts/                # 框架制品，不是框架源码
└── .gitignore + pyproject.toml + uv.lock + framework-baseline.json
```

从源码维护示例时，两服务位于 framework/examples/services；本文命令只面向发行后的新项目根。初始化 Git 后先用 git check-ignore 核对 .local/environment.env 与服务 .venv/target 被忽略。

## 2. 工具与本地依赖

本地候选随附独立的 `framework-1.0.0-rc.3-images.tar` 及 `framework-1.0.0-rc.3-offline.json` 摘要。迁入另一台 Linux/amd64 Docker 主机时，先核对 archive SHA-256，再执行 `docker load --input <archive>`；发行清单的 sha256 镜像 ID 即可在本机解析。PG/Redis/Nacos 等官方组件和依赖下载仍需已缓存或可访问仓库，这不是全依赖离线安装包。服务器正式发布仍需团队镜像仓库的 repository@sha256 引用。

前提：Docker Engine + Compose、Python 3.12 + uv；构建 Java 时需要 JDK 21。Windows 使用 PowerShell、mvnw.cmd；Linux 使用 ./mvnw。以下命令的工作目录均为发行目录根。

宿主机使用 HTTP 代理时，将 `localhost,127.0.0.1,::1` 加入 `NO_PROXY`，否则本地迁移后发布权限、浏览器回调等验证可能误走代理；不要用 `*` 关闭所有环境的代理策略。

```bash
uv sync --frozen
```

复制 config/environments/local.env.example 到 .local/environment.env，先填写 PROJECT_CODE、PUBLIC_ORIGIN；修改同项目前缀的 Nacos 用户名。另一项目使用不同的公开端口、PG/Redis/Nacos 映射端口和对应连接端口。Nacos gRPC 映射端口必须等于客户端 API 映射端口加 1000。不要复制另一项目已生成的秘密。

```bash
uv run --frozen python infra/init/run.py --env-file .local/environment.env --phase prepare-local
uv run --frozen python infra/up.py --env-file .local/environment.env
uv run --frozen python infra/init/run.py --env-file .local/environment.env --phase resources
uv run --frozen python config/publish.py --env-file .local/environment.env
```

默认只启动 PG、Redis、Nacos，创建身份库、受限运行账号及独立 Nacos namespace。RabbitMQ 只在项目明确需要消息时启用，并单独初始化 infra/resources-mq.yaml。外部组件由环境 MODE 指定，不再安装容器；外部模式按正式接入指南绑定地址和运行凭据，不使用下面的自建组件示例绑定脚本。

## 3. 先迁移、再初始化身份

```bash
uv run --frozen python deploy/scripts/migrate.py --env-file .local/environment.env --target identity-db --tool flyway --migrations-dir artifacts/identity-migrations --yes
uv run --frozen python examples/prepare_local.py --env-file .local/environment.env
docker compose --env-file .local/runtime/compose.env -f deploy/compose.yaml run --rm identity-bootstrap
docker compose --env-file .local/runtime/compose.env -f deploy/compose.yaml -f deploy/compose.local.yaml up -d --wait identity-service api-gateway nginx
```

prepare_local.py 只适用于本地示例的自建组件/default 资源命名；生成独立运行绑定、首次密码、BFF 秘密与签名密钥，已有不一致文件拒绝覆盖。它不登记业务客户端、不分配业务角色、不重置账号。运行服务只挂载自己的秘密目录；组件管理员环境文件不传给应用。

浏览器打开 PUBLIC_ORIGIN/identity/admin/，选择平台管理员。初始用户名 admin，初始密码保存在 .local/runtime/identity-bootstrap-secrets/admin-initial.password，首次登录强制改密。命令行不打印密码。

## 4. 构建业务，始终引用制品

Java 制品没有公开仓库时，候选包可先安装发行附带的 POM/JAR 到本机 Maven 仓库（正式团队使用自己的 Maven 仓库）。以下 Windows 示例使用现有 wrapper：

```powershell
./mvnw.cmd org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file '-Dfile=artifacts/java/platform-parent-1.0.0-rc.3.pom' '-DpomFile=artifacts/java/platform-parent-1.0.0-rc.3.pom'
./mvnw.cmd org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file '-Dfile=artifacts/java/foundation-1.0.0-rc.3.jar' '-DpomFile=artifacts/java/foundation-1.0.0-rc.3.pom'
./mvnw.cmd -f services/sample-java-service/pom.xml clean package
docker build -t local/sample-java:0.1.0 services/sample-java-service
```

Python 的 requirements.lock 固定传递依赖及源摘要，SDK wheel 来自发行包。为 Linux 镜像先在 Linux 构建环境准备 wheels（源发行包用 pip wheel 构建，不能假设每个依赖都有预编译 wheel），再使用示例 Dockerfile 离线安装：

```bash
uv build --project services/sample-python-service --wheel --out-dir services/sample-python-service/wheels
```

将 artifacts/python 中 SDK wheel 复制到上述 wheels 目录；在 Python 3.12 Linux 构建环境执行 `pip wheel --require-hashes --find-links=<wheels> -r requirements.lock -w <wheels>`，然后 `docker build -t local/sample-python:0.1.0 services/sample-python-service`。SDK 与项目 wheel 须为当前发行，wheels 必须是本次 Linux 锁文件对应的干净目录，不能混入旧 SDK、旧依赖或 Windows wheel。Dockerfile 显式安装这些 wheel 并执行 pip check，避免重新解析时拒绝已经锁定的预发行依赖。升级 SDK 后重新锁定依赖并导出 requirements.lock；uv 对本地 find-links wheel 可能不导出摘要，须按实际发行 wheel 的 SHA-256 补齐该项 --hash，不能跳过 --require-hashes。Windows Docker 的 volume 路径需用绝对路径。Python API 入口为 sample-api；迁移入口独立使用 Alembic，不启动 API。

## 5. 业务数据库和权限目录

对两个服务分别初始化资源并迁移，运行账号的表权限由业务迁移显式授予：

```bash
uv run --frozen python infra/init/run.py --env-file .local/environment.env --phase resources --resources services/sample-java-service/resources.yaml
uv run --frozen python deploy/scripts/migrate.py --env-file .local/environment.env --resources services/sample-java-service/resources.yaml --target sample-java-db --tool flyway --migrations-dir services/sample-java-service/src/main/resources/db/migration --yes
uv run --frozen python infra/init/run.py --env-file .local/environment.env --phase resources --resources services/sample-python-service/resources.yaml
uv run --frozen python deploy/scripts/migrate.py --env-file .local/environment.env --resources services/sample-python-service/resources.yaml --target sample-python-db --tool alembic --migrations-dir services/sample-python-service/migrations --yes
```

使用 bootstrap 输出的对应 PUBLISHER client_id，通过 deploy/scripts/sync_permissions.py 发布各自 permissions.yaml。参数：--identity-origin（管理机可达的身份服务 origin）、--app-env、--client-id、--secret-file（bootstrap 中对应服务的发布密码文件）、--manifest、--release-version。本地 HTTP 仅允许 loopback。这个内部 API 不从 Nginx/Gateway 公开，管理机应经受控网络/TLS 或本地 loopback 端口访问。

两个 `--manifest` 的实际路径分别为 `services/sample-java-service/src/main/resources/permissions.yaml` 和 `services/sample-python-service/src/sample_service/resources/permissions.yaml`；它们不在服务根目录。`--secret-file` 分别指向 `.local/runtime/identity-bootstrap-secrets/sample-java-service.password` 与 `sample-python-service.password`。`--client-id` 必须取 bootstrap 输出的对应 PUBLISHER 值，不能用服务名代替。

发布成功只让权限进入目录，新权限不会自动授予普通用户。

本地覆盖文件 `deploy/compose.local.yaml` 只把身份管理端口绑定到 127.0.0.1。prepare_local 生成的 IDENTITY_ADMIN_PORT 默认为公开入口端口加 1。发布清单使用 `http://localhost:<IDENTITY_ADMIN_PORT>`，业务和浏览器仍只使用公开入口。正式环境不用这个覆盖文件。

## 6. 平台登记与业务授权

平台管理员在“应用与机器身份”页登记服务 audience；对应接口 POST /api/v1/identity/platform/service-audiences，body 为 service/audience 两个字段；此示例二者均为 sample-java-service 或 sample-python-service。相同绑定可重复，冲突拒绝。

平台管理员创建租户，然后在“应用与机器身份”页面登记（页面调用客户端管理 API）：

| 客户端 | 关键限制 |
| --- | --- |
| 每个资源服务的内省客户端 | service 类型；audiences=[identity-service]；仅一个 internal.introspect:<目标 audience> scope；无租户绑定 |
| Gateway 每个目标 audience 的内省客户端 | 同上，但 client_id/秘密必须与业务服务分开 |
| web-bff | user 类型、机密客户端、first_party=true；allowed_tenant_ids 是本项目明确的租户；audiences 含 identity-service 和两个业务服务；scopes 含 profile、java.items.read/write、python.items.read/write；准确回调 PUBLIC_ORIGIN/bff/web/callback；退出回调 PUBLIC_ORIGIN/bff/web/logged-out |

客户端新建只返回一次 client_secret，保存到对应服务的秘密目录。不能直接 INSERT 身份库，不为方便共用管理员/内省凭据。租户管理员创建普通业务角色、选择所需权限和 SELF/TENANT 范围，将角色授给用户。平台管理员身份不直接访问业务租户数据。

## 7. 启动业务

按 examples/config/runtime.env.example 为两个服务各自创建独立 runtime/<service>.env；只挂载自己的数据库和内省秘密。Python 使用端口 8083，Java 使用 8082。分别设置对端 PEER_SERVICE，以此验证同一项目内跨语言调用。

登记完成后，Gateway 的 bff.json 才增加 web（secret_file、requested_scopes）；Gateway 环境增加 GATEWAY_INTROSPECTION_CLIENTS_SECRET_FILE 指向 readers.json，其形状为 readers 数组，每项 audience/client_id/secret_file。逐项与将发布的路由对应。

将 examples/config/nacos 中所选服务的 YAML 与 Sentinel JSON 一并合入项目 config/nacos，发布业务路由前必须先完成 Gateway 内省凭据；只保留项目实际启用的路由。随后重启 Gateway 和启动业务：

```bash
uv run --frozen python config/publish.py --env-file .local/environment.env
docker compose --env-file .local/runtime/compose.env -f deploy/compose.yaml -f deploy/compose.local.yaml -f examples/compose.yaml --profile java --profile python up -d --wait sample-java-service sample-python-service api-gateway
docker compose --env-file .local/runtime/compose.env -f deploy/compose.yaml -f deploy/compose.local.yaml exec nginx nginx -t
docker compose --env-file .local/runtime/compose.env -f deploy/compose.yaml -f deploy/compose.local.yaml exec nginx nginx -s reload
```

compose.env 需补齐 SAMPLE_JAVA_IMAGE/SAMPLE_PYTHON_IMAGE。浏览器打开 PUBLIC_ORIGIN，输入租户代码登录业务。创建/查询资源、访问 peer 证明双向 HTTP；未授权用户应被拒绝。请求中的 tenant_id/owner 不作为可信身份来源。

示例 Nginx 在启动时解析 Gateway 容器名，Gateway 重建后必须检验并 reload Nginx。两个示例镜像默认分别为 local/sample-java:0.1.0、local/sample-python:0.1.0；混合示例同时启用两个 profile、路由与配置；业务项目以后明确不需要某服务时，再连同资源和凭据声明一起移除。正式发布必须使用已验证的不可变镜像引用。

若需要自动重放这条本地示例链，见 [独立项目验证](verification/README.md)，里面包含正式 API 登记和真实浏览器验证脚本。验证工具不进入业务镜像。

## 8. 升级与排错

业务源码由项目维护；升级框架父 POM/SDK 包、基础服务镜像及所需运维材料。先阅读兼容说明、备份、执行迁移并验证，再分批更新应用。更新身份服务时先单独等待 identity-service ready，再更新依赖内省的业务容器；不要用 --no-deps 同时重启身份和业务后把启动失败当作发布成功。不覆盖项目秘密，不重建 infra 数据卷。镜像回退不能自动回滚破坏性数据库变更。

- 服务不 ready：检查配置来源、Nacos 运行账号、服务自己的 DB/内省秘密及迁移版本；不要改成永远健康。
- 浏览器跳到容器地址：核对身份镜像包含相对跳转配置，PUBLIC_ORIGIN 与实际入口一致。
- 401：检查登录、应用租户/audience/scope 和令牌撤销；403：检查角色、权限及数据范围。
- 目录版本冲突：停止发布，读最新版本、核对变更后重新提交；不自动覆盖。
- prepare_local 报已有文件冲突：检查差异并按升级/轮换流程处理，不能删除秘密后重建。

本次候选移除了管理委派、授权模拟和租户策略编辑。旧委派不会转换为管理员；有历史租户策略的环境必须在旧版本中把项目策略统一到至少同等保护并核对影响，再切换新镜像。新实例启动检查不通过时保留旧实例，按报错核对，不删除策略数据、不自动降级保护。第三方登录和消息须显式启用，分别见 [第三方登录](federation/README.md) 与 [可选消息](messaging/README.md)。服务器发布工具已交付，本地验证与真实环境验收分别记录；旧候选包不自动包含新能力。


<a id="混合调试"></a>

## 混合调试

工作目录仍是独立项目根。先跑通全容器，再切换一个业务进程。所有会被 IDE 调用的实例（包括身份和对端业务）的 Nacos 注册地址都必须对宿主可达；只改当前服务的 SERVICE_ADVERTISE_HOST 不够。此处采用 Compose 明确端口映射和名称解析，不在 SDK 中增加另一套路由。

1. 在 compose.env 选择 `DEV_HOST`：它在宿主必须解析到本机，容器由 compose.host.yaml 的 extra_hosts 显式映射到 host-gateway。Docker Desktop 的 host.docker.internal 是否可从宿主解析需要实际检查；不成立时先配置自己的本地域名，不改为跳过鉴权。`DEV_BIND_HOST=127.0.0.1`、`JAVA_HOST_PORT=48082`、`PYTHON_HOST_PORT=48083`；避免暴露所有网卡。正式服务器不用此覆盖。
2. 使用当前基础 Compose 加 `examples/compose.yaml`、`examples/compose.host.yaml` 更新 identity/Gateway/所选业务，使登记地址一致；Gateway 重建后检验并 reload Nginx。从宿主和 Gateway 容器各访问 identity readiness，确认到达的是本项目实例。
3. 复制 config/environments/local-host.env.example 为该服务独立 host 文件，写宿主 PG/Nacos 映射端口、该服务秘密文件绝对路径、宿主监听和注册地址。机器有系统代理时将 DEV_HOST、localhost、127.0.0.1 明确写入 NO_PROXY，不用通配符。
4. 停止待调试容器，保留其他实例；用 dev.ps1/dev.sh 启动登记的业务。Java 先构建 JAR；Python 从发行 SDK 创建自己的环境，例如 `uv sync --frozen --project services/sample-python-service`。业务工作树可 editable；框架 SDK 必须是固定 wheel。

```powershell
./deploy/scripts/dev.ps1 -Service sample-python-service -Role api -EnvFile .local/runtime/sample-python-service.env -HostEnvFile .local/python-host.env -Check
./deploy/scripts/dev.ps1 -Service sample-python-service -Role api -EnvFile .local/runtime/sample-python-service.env -HostEnvFile .local/python-host.env
```

配置登记在 config/development.yaml，只接受服务目录、Java JAR 或 Python console entry。Ctrl+C 停止宿主进程后再启动其容器；从公开入口重测登录与双向调用。调试器附加属于所选 IDE 配置，启动入口不建设另一套调试平台。

## 候选升级

保留项目原 framework-baseline.json；新包解压到另一个目录，先逐项比较原基线、新包和项目当前文件。业务源码、config/nacos、infra/resources、环境和秘密归项目所有，不能整体覆盖。仅更新审核过的运行工具与示例差异；**根 pyproject.toml 和 uv.lock 必须与迁移工具一起更新，并在项目根执行 uv sync --frozen**，否则新工具可能缺 Alembic 等依赖。

升级 SDK 后重新构建业务镜像并锁定摘要；基础服务换已验证的同版镜像。先备份并验证可恢复，再用本发行 artifacts/identity-migrations 执行身份迁移，业务库执行自己的迁移，全部成功才逐个更新应用。升级不重跑 bootstrap，不改变原账号、角色、租户 ID，不覆盖签名密钥；管理页随身份镜像更新。

本地从 slim-c02/V8 到 rc.1/V9 的旧库迁移及兼容应用回退有独立验证记录。V9 是新增绑定表，回退前提是第三方入口未启用且旧镜像已移除委派/模拟；这不是允许任意旧版本回退。数据库恢复测试使用隔离新库，不能直接覆盖运行库。实际多机与外部提供方仍待环境验收。

服务器入口、证书与失败恢复的具体材料见 [发布操作说明](../deploy/ansible/README.md)。候选可使用随附离线镜像归档迁入本机 Docker；正式跨机发布需推到团队获准的镜像仓库并取得 repository@sha256 引用，当前未对外发布。

## 9. 流量保护

示例网关为 Java/Python 路由配置 RedisRateLimiter（每秒补充 20 令牌、容量 40）；BFF 自动派生并共用桶。Java POM 显式带 Sentinel core/datasource-nacos，配置含 enabled 与两份 JSON；Python 的 runtime.install(app) 在资源安全安装前调用，入站 64、对 Java 出站 32。数值只是演示，正式项目按压测调整。

参数来自 config/nacos，环境地址与秘密仍在 runtime env/秘密文件。用现有 config/publish.py 发布：JSON 有效变更动态应用，路由/池/Python 参数需要重启对应服务。Java 缺少规则不注册，运行中无效规则保留旧值；明确清空用 []。检查每个 Java 实例日志中的 dataId/sha256，不能把发布成功当成所有实例同时应用。

Python 默认不带通用熔断器；使用 runtime.http 获得并发与连接池保护。Java 使用注入的 ServiceHttpClient，不额外加第二层埋点。429 是租户/机器额度不足；503 SERVICE_BUSY/DEPENDENCY_BUSY 是容量不足；503 DEPENDENCY_CIRCUIT_OPEN 是 Java 依赖熔断；Redis 故障为 503 RATE_LIMIT_UNAVAILABLE。写操作不自动重试。
