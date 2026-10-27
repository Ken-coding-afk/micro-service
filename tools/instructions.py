"""生成项目操作说明；命令使用生成时已知名称。"""


def service_readme(name: str, language: str, package: str) -> str:
    migrations = "src/main/resources/db/migration" if language == "java" else "migrations"
    permissions = (
        "src/main/resources/permissions.yaml"
        if language == "java"
        else f"src/{package}/resources/permissions.yaml"
    )
    tool = "flyway" if language == "java" else "alembic"
    return f"""# {name} 接入清单

工作目录：项目根。源码、测试、迁移和 Nacos 路由已生成；尚未给任何用户新增权限。对端调用默认不启用。

1. 在平台管理页登记 service/audience={name}；为该服务创建 catalog.publish:{name} 的独立发布客户端。
2. 初始化资源并迁移。下面用 Windows PowerShell；Linux 将 `$toolPython` 换成 `.local/tools-venv/bin/python`。工具环境由项目 init 建立，不在 vendor 中创建另一个环境：

```powershell
$toolPython = '.local/tools-venv/Scripts/python.exe'
& $toolPython vendor/micro-service/tools/infra/init.py --env-file .local/environment.env --phase resources --resources services/{name}/resources.yaml
& $toolPython vendor/micro-service/tools/deploy/migrate.py --env-file .local/environment.env --resources services/{name}/resources.yaml --target {name}-db --tool {tool} --migrations-dir services/{name}/{migrations} --yes
```

3. 将发布客户端秘密保存在 `.local/{name}-publisher.password`，使用实际身份端口（18081 + port-offset）及第一步的客户端 ID 发布权限，不手工写身份库：

```powershell
& $toolPython vendor/micro-service/tools/deploy/sync_permissions.py --identity-origin http://localhost:<身份端口> --app-env local --client-id <发布客户端ID> --secret-file .local/{name}-publisher.password --manifest services/{name}/{permissions} --release-version local-initial
```
4. 在管理页创建 {name}-resource 和 {name}-gateway 两个 service 客户端，audiences=[identity-service]，各只给 internal.introspect:{name}；保存到各自秘密目录。更新 web-bff 的 audiences/scopes，租户管理员再给角色分配新权限。
5. 参照初始服务 .local/runtime 文件创建 {name}.env 和 {name}-host.env；只改为本服务的 DB/秘密/端口/audience/注册名，不复用另一个服务秘密。未配置 PEER_SERVICE 时 /peer 返回 PEER_NOT_CONFIGURED。
6. 将独立 Gateway reader 追加到 api-gateway-secrets/readers.json，将业务 scope 追加到 bff.json.web.requested_scopes；执行以下发布及重建命令。手工改过的派生文件不能再由首次 init 覆盖。

```powershell
& $toolPython vendor/micro-service/tools/config/publish.py --env-file .local/environment.env --source config/nacos
docker compose --project-directory . --env-file .local/runtime/compose.env -f deploy/compose.yaml -f .local/runtime/host.compose.yaml up -d --wait --force-recreate api-gateway
docker compose --project-directory . --env-file .local/runtime/compose.env -f deploy/compose.yaml -f .local/runtime/host.compose.yaml exec -T nginx nginx -t
docker compose --project-directory . --env-file .local/runtime/compose.env -f deploy/compose.yaml -f .local/runtime/host.compose.yaml exec -T nginx nginx -s reload
```
7. python manage.py dev {name}；访问 /api/v1/{name}/items。构建与部署时将本服务 Compose、运行绑定和迁移纳入自己的发布清单。

上述是显式管理员接入流程；init --local 只复现创建项目时的两个演示服务，不是授权同步器。
"""


def readme(config: dict) -> str:
    java, python = config["local"]["initial_services"]
    project = config["local"]["project"]
    port = 18080 + config["local"]["port_offset"]
    return f"""# {project}

这是一个 Java/Python 混合微服务项目。业务代码和配置归本仓库；固定框架源码在 vendor/micro-service。身份管理页内置 identity-service，不复制到业务 Web。源码引用无需 Nexus/Maven 私库/PyPI 私库，第三方依赖仍需网络。

## 1. 创建后先保存项目

前提：Git、Python 3.12、uv、JDK 21、Docker Engine + Compose。Docker Desktop 须已启动，Linux 指定容器可访问的宿主私网 IP。工作目录均为本项目根。

```bash
git status --short
git check-ignore .local/environment.env services/{python}/.venv
git add .
git commit -m "初始化混合项目"
```

团队自行添加业务远程并 push，创建器不自动提交或上传。新机器 git clone --recurse-submodules；已有目录缺 vendor 时运行 git submodule update --init --recursive。不要 git submodule update --remote。

## 2. 初始化并启动

```bash
python manage.py init --local
```

Linux 使用 `python manage.py init --local --dev-host <本机私网IP>`。Docker Desktop 默认 host.docker.internal，需宿主能解析且容器能访问；不能用 localhost 注册宿主业务。入口/PG/Redis/Nacos 端口由创建时的 port-offset 成组隔离，Nacos gRPC=API+1000。冲突会停止；不自动修改 hosts 或防火墙。本地身份发布端口和宿主业务监听网卡供 Docker 访问，用防火墙只允许开发来源。

初始化会构建基础镜像，同步 Python 独立环境，启动 PG/Redis/Nacos，创建受限资源，迁移，初始化平台账号，发布业务权限，安装两个演示租户和各自的 admin/writer/denied，最后启动 Gateway/Nginx。首次下载和构建可能较久，阶段失败会停止；修正后同命令重跑，不删除卷或重置秘密。迁移 UNKNOWN 需人工核查。

两个终端分别运行：

```bash
python manage.py dev {java}
python manage.py dev {python}
```

Java 自动从 reactor 构建；Python frozen sync 使用独立 .venv、固定 SDK 源码。启动后工具从宿主及 Gateway 容器探测 readiness。Ctrl+C 只停止本次进程；普通开发无需先构建业务镜像。

管理页 http://localhost:{port}/identity/admin/；业务页 http://localhost:{port}/。平台初始 admin 密码在 `.local/runtime/identity-bootstrap-secrets/admin-initial.password`。租户代码 alpha/beta，各自用户 admin/writer/denied 的初始密码在同目录 `<租户>-<用户>.password`。首次均需改密；writer 有两服务示例权限，denied 没有权限。先登录 writer 改密，再创建/查询资源并访问对端；换 denied 应拒绝，换租户应看不到其他租户记录。普通平台管理员不访问业务租户数据。

## 3. 日常开发与新增服务

Java IDE 导入根 pom.xml；可用 `python manage.py dev {java} --debug` 启动后附加 127.0.0.1:5005。Python 选 services/{python}/.venv 的解释器；每服务用自己的 uv.lock，新增依赖在该服务执行 uv add，不修改其他服务。VS Code tasks 提供初始化和启动命令。

业务改 services、web、config/nacos；默认值在服务，路由/可调参数经 Nacos 发布，秘密只在 .local。初始化后增加权限按正式发布工具同步并由管理页分配，不再重跑首次演示身份安装。若初始清单变动，init 明确停止，不恢复旧角色。

```bash
python manage.py add-service --language java --name report-service --package com.acme.report --port 48084
python manage.py add-service --language python --name scoring-service --package scoring --port 48085
```

按新服务 README 的资源、权限、客户端和绑定清单完成接入后启动。新增服务的权限/路由按服务名独立生成，不继承另一个服务的权限，不修改框架源码。

## 4. 镜像与部署

```bash
python manage.py build --release 0.1.0 --output dist/0.1.0-local
python manage.py build --release 0.1.1 --service {python} --output dist/0.1.1-python
```

完整构建从固定源码生成两个基础服务和业务镜像；Python 在 Linux 内非 editable 安装，不需要手工 wheelhouse。局部构建不是完整发行。业务 Web 默认使用 web/index.html；有 Vite 等构建的项目先构建，再在正式打包时传 --web-root 指向静态产物。

正式发布按以下顺序操作；前面的构建命令可用于开发检查，不能替代此顺序：

1. **构建前**复制 deploy/release.yaml.example 为 deploy/release.yaml，填写 owner、approval_reference、rollback_window_hours、compatible_previous_versions 及兼容策略；完成本次业务代码、配置、迁移和依赖锁。
2. 提交全部构建输入（包括发布策略和框架 gitlink），将项目及框架提交推到可获取远程，保持工作树干净，再构建完整镜像。
3. 由获准运维 push 镜像，取得 repository@sha256，镜像映射保存到被 Git 忽略的 .local/images-production.json；环境秘密也放在 .local 或仓库外，不再改源码或发布策略。
4. 用同次构建记录执行下面的 package-only。如果中途修改发布策略、代码或锁文件，先提交并重新构建；不能沿用旧镜像记录。

源码中的 deploy/release.yaml 是发布策略输入；部署包中的同名文件由打包器补全源码、镜像、迁移及文件摘要，不手工计算或重写。主机/域名/TLS/环境秘密按固定框架 tools/deploy/ansible/README.md 准备。

```bash
python manage.py build --release 0.1.0 --output dist/0.1.0-test --package-only --images .local/images-production.json --env-file <测试环境文件>
```

打包核对上传镜像与本次构建 image ID、源码提交和文件摘要。部署载荷不带源码/.local/秘密；运维在 Linux/WSL 上按包内 tools/deploy/ansible/README.md 执行 initialize → migrate → 首次 bootstrap → deploy → 验收。正式单机/多机都走镜像仓库；没有新离线预载发布模式。缺真实双机、TLS 或第三方提供方验收时不能称为生产已验证。

## 5. 升级与已有项目

建升级分支，记录旧 gitlink/镜像；在 vendor 中 fetch 并 checkout 明确提交，更新父 POM/SDK 版本约束及各服务锁，运行受影响测试和本地登录/调用/隔离验收，再把 gitlink 与锁文件一起提交。不要重新生成业务目录，不覆盖角色、账号、秘密和卷。工具跟随 gitlink，运行升级另做备份、迁移、镜像发布。

已有 rc.3 制品项目一次性增加 vendor、根 reactor、SDK source 路径，保留原服务/权限/audience/资源名及数据；不运行新项目 init，不自动改名。按固定框架 docs/design/10-数据库迁移与升级设计.md 的兼容规则验证。开发引用回退不等于数据库回退。

## 6. 检查与排错

- 规范：vendor/micro-service/docs/standards/01-项目编码规范.md、02-Java编码规范.md、03-Python编码规范.md。项目补充只写 docs，不复制框架历史计划。
- Java 在根 reactor 用 -pl :{java} -am 加测试过滤；Python 在自身目录 uv run --frozen pytest tests/test_business_config.py tests/test_items.py tests/test_application.py。只跑受影响测试。
- 401 检查 issuer/audience/scope/撤销；403 检查角色和数据范围，不关闭鉴权。
- ready 失败检查 Nacos、PG 和本服务内省秘密；宿主就绪但容器失败检查 DEV_HOST/监听/防火墙。
- 生成文件冲突时先审查具体差异，别删除秘密或卷。首次身份初始化不同定义停止，后续授权走管理页。
- 升级、初始化、构建都不自动 push 或部署；dist 与 .local 必须被 Git 忽略。
"""
