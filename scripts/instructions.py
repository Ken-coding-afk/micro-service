"""随生成项目交付的独立操作说明；不引用维护仓库的磁盘路径。"""


def service_readme(name: str, language: str, package: str) -> str:
    return f"""# {name}

本服务的正常开发位置为宿主项目 services/{name}；SDK 来自 ../../sdk/{"java" if language == "java" else "python"}。初始服务属于主仓，独立新服务以固定 submodule 挂载。不把此服务单独 clone 到任意目录后假定能直接构建。

在宿主根执行 uv run --frozen python scripts/init.py --env local --services {name}。技术登记与权限目录发布不等于给用户授权；平台管理员允许客户端的租户及 scope/audience，租户管理员分配角色权限后重新登录。然后 F5 service:{name} 或 uv run --frozen python scripts/dev.py {name}。

API：/api/v1/{name}/items。未配置对端时 peer 演示返回 PEER_NOT_CONFIGURED。新增服务仓库的 config/nacos 只用于第一次接入时提供初始值，接入后的运行配置归宿主 config/nacos；升级不覆盖宿主配置。

迁移在本服务维护，已共享版本不可改；测试只选受影响用例。子仓开发先建工作分支，先提交/push 子仓，再提交主仓 gitlink。不要 git submodule update --remote。
"""


def readme(config: dict) -> str:
    java = next(name for name, v in config["services"].items() if v["language"] == "java")
    python = next(name for name, v in config["services"].items() if v["language"] == "python")
    project = config["local"]["project"]
    port = 18080 + config["local"]["port_offset"]
    return f"""# {project}

本项目直接拥有 sdk/java、sdk/python、identity-service、api-gateway 与初始双语业务。没有框架 vendor、template 或私有 SDK 仓库依赖。身份管理页随身份服务；web 为业务页面。第三方依赖需要网络，运行证据由本项目验收记录确认。

## 1. 从创建到初始化

准备 Git、Python 3.12、uv、JDK 21、Docker Engine/Compose。以下均在项目根，创建本身未启动容器或安装业务依赖：

```bash
uv sync --frozen
./mvnw.cmd -pl :{java} -am spotless:apply
uv lock --project services/{python}
uv sync --frozen --project services/{python}
uv run --frozen python scripts/init.py --env local --services all
```

Windows 用 mvnw.cmd，Linux/macOS 用 ./mvnw；生成后的首次依赖准备同时格式化改名后的 Java 源码。首次创建才生成业务锁，提交后使用 frozen sync；不要每次启动重新 lock。体验演示时 init 显式加 --with-demo；Linux 加 --dev-host <容器可达宿主私网地址>。Docker Desktop 的 host.docker.internal 需双方可解析；不修改 hosts/防火墙，不用 localhost 注册容器需访问的宿主服务。

init 从本项目构建开发和 Python 迁移镜像，安装/核对组件资源，先发布 Nacos 配置再启动身份，登记技术客户端/发布目录/迁移业务，最后启动入口。失败停止并保留数据，不删卷重试；基础环境 ready 不等于业务启动。配置与端口在 config；秘密/运行绑定在忽略的 .local。

## 2. 首次登录与授权

默认管理页 http://localhost:{port}/identity/admin/，业务页 http://localhost:{port}/。以 init 输出 URL 为准，首次平台密码路径为 .local/runtime/identity-bootstrap-secrets/admin-initial.password；日志不打印密码。

不带演示数据：平台管理员首次登录改密→创建租户与租户管理员→客户端管理为业务 BFF/Swagger 允许租户→租户管理员创建普通用户和角色、分配已发布业务权限→普通用户登录业务页。空 allowedTenantIds 不代表全部，平台管理员不是租户业务账号。

--with-demo 才创建 alpha/beta 及 admin/writer/denied；初始密码各在 .local/runtime/identity-bootstrap-secrets/<租户>-<用户>.password。先完成首次改密，writer 可写读，denied 拒绝；不同租户记录互不可见。重跑不重置密码、不为新增服务自动扩权。

## 3. 本地运行、F5 与 Swagger

从项目根打开 VS Code，安装推荐 Java/Python/debugpy 扩展。F5 选择 service:{java}、service:{python} 或 services:combined；preLaunch 只准备运行绑定，不迁移、不修改授权。Java 直接调试源码，Python 使用本服务 .venv。

```bash
uv run --frozen python scripts/dev.py {java}
uv run --frozen python scripts/dev.py {python}
```

Swagger 调试在 dev 命令加 --tenant-code <租户编码>；F5 准备任务会提示输入（不使用 Swagger 可留空）。这只设置授权请求参数，登录仍验证真实租户。从服务文档地址进入：Authorize 选 scope→组织登录→同意→Try it out。公开客户端使用 PKCE，不填 secret，不从 BFF 导出 token。token 过期重新授权；新增权限后管理员调整客户端与角色，退出旧业务会话重新登录。401 检查 token/issuer，403 检查角色、scope/audience、客户端租户限制。

停止只结束本次服务进程。容器状态、日志、停止使用生成的 Compose tasks，不 down -v。宿主能调用但网关失败时查 Nacos 注册地址、监听网卡与防火墙。调试停在断点可能触发超时，不自动关闭保护。

## 4. 新增服务和协作

新服务在兼容版本脚手架仓库运行 create_service.py --project-root <本项目绝对目录>，从标准样例生成独立目录；项目不保存第二份样例。自己提交/push 服务仓库后，在本项目：

```bash
uv run --frozen python scripts/add_service.py --repo <远程URL> --ref <完整提交> --language python --name scoring-service --package scoring --port 48085
uv run --frozen python scripts/init.py --env local --services scoring-service
```

按输出完成管理员客户端范围/租户绑定和角色授权，再重新登录。新服务默认没有 peer 调用。初始两业务直接提交主仓；后续 submodule 先 push 子仓再提交主仓 gitlink。另一开发者 git clone --recurse-submodules，独立 local 不需要 A 的 .local；共享环境从受控渠道取回原秘密，不生成替代密码。

## 5. SQL 与测试

Java 在服务 src/main/resources/db/migration 新增递增 V 文件。Python 在服务目录 uv run --frozen --group migration alembic -c migrations/alembic.ini revision -m <动作> 后审查 upgrade。在项目根：

```bash
uv run --frozen python scripts/migrate.py --env local --service {python} --check
uv run --frozen python scripts/migrate.py --env local --service {python} --apply
```

check 不写库；已在共享环境执行的迁移不能修改。新增可空列、回填/非空、改名和删除按项目发布评审处理；删除重建不恢复数据。迁移 unknown 先核对历史和执行进程，不盲目重跑。业务运行账号无 DDL。

规范在 docs/standards。Java 根 mvnw.cmd -pl :{java} -am 加相关测试过滤、Spotless；Python 服务目录执行定向 pytest/ruff/mypy。不能以编译通过代替真实登录、租户隔离和迁移验收。

## 6. 部署和升级

先复制 deploy/release.yaml.example 为 deploy/release.yaml，填写环境、负责人、确认记录和兼容/维护迁移模式。正式发布前提交全部源码/配置/锁/发布策略，子仓固定且干净，提交可由团队取回。

```bash
uv run --frozen python scripts/build.py --release 0.1.0 --services all --output dist/0.1.0
```

局部发布使用 --services <服务名> 并加 --base-release <已部署发行清单>；基础输入变化会要求完整重建。按输出镜像列表 Docker/CI push 并采集真实 repository@sha256 至 .local/images-production.json，再打包：

```bash
uv run --frozen python scripts/package.py --build-record dist/0.1.0/build-record.json --images .local/images-production.json --output dist/0.1.0-package
```

在 Linux/WSL 控制机的部署包根 uv sync --frozen。按 deploy/ansible/README.md 将 config/environments/test/hosts.yaml.example 复制为该目录 hosts.yaml，填写实际主机/路径；生产用独立 inventory。准备包外环境/TLS/秘密/镜像权限和备份材料；单机也用一台主机 inventory。首次按 infra.yaml→initialize.yaml→migrate.yaml→deploy.yaml：

```bash
ansible-playbook -i config/environments/test/hosts.yaml deploy/ansible/infra.yaml --extra-vars @/secure/project-prod.yaml
ansible-playbook -i config/environments/test/hosts.yaml deploy/ansible/initialize.yaml --extra-vars @/secure/project-prod.yaml
ansible-playbook -i config/environments/test/hosts.yaml deploy/ansible/migrate.yaml --extra-vars @/secure/project-prod.yaml
ansible-playbook -i config/environments/test/hosts.yaml deploy/ansible/deploy.yaml --extra-vars @/secure/project-prod.yaml
```

部署包独立运行，不需要原源码/.local；生产不带演示账号。管理员完成租户/客户端/角色设置后验证普通用户真实登录、允许/拒绝和隔离。局部服务包不能冒充首次完整环境。

升级在分支合并公共源码补丁或更新指定子仓，核对迁移与锁后重新构建。先预拉取/校验镜像和备份，再选兼容迁移或维护窗口。破坏性变更选择 migration_mode=maintenance，必须停流量、旧应用与所有相关写入者；记录 maintenance_evidence，明确 maintenance_writers_stopped=true 后迁移→新版本验收→恢复流量。失败保持维护状态并 reconcile；不自动降级数据库。旧 vendor 项目仅有迁移检查清单，需真实项目单独验证。
"""
