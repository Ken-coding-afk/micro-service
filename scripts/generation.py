"""从根目录公共源码与 services 中的样例生成 Java/Python 混合项目。"""

from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path

from common import FRAMEWORK, read_yaml, source_files, write, write_yaml
from registration import register, replace, replacements, validate_name, validate_package


def service_readme(name: str, language: str, package: str, source: str = "submodule") -> str:
    ownership = (
        "本服务归主仓，直接随主仓提交；无需独立远程仓库或gitlink。"
        if source == "local"
        else "本服务作为固定submodule接入；先提交/push子仓，再提交主仓gitlink。"
    )
    return f"""# {name}

本服务的正常开发位置为宿主项目 services/{name}；SDK 来自 ../../sdk/{"java" if language == "java" else "python"}。{ownership} 不把此服务单独 clone 到任意目录后假定能直接构建。

在宿主根执行 uv run --frozen python scripts/init.py --env local --services {name}。技术登记与权限目录发布不等于给用户授权；平台管理员允许客户端的租户及 scope/audience，租户管理员分配角色权限后重新登录。然后 F5 service:{name} 或 uv run --frozen python scripts/dev.py {name}。

API：/api/v1/{name}/items。未配置对端时 peer 演示返回 PEER_NOT_CONFIGURED。新增服务仓库的 config/nacos 只用于第一次接入时提供初始值，接入后的运行配置归宿主 config/nacos；升级不覆盖宿主配置。

迁移在本服务维护，已共享版本不可改；测试只选受影响用例。{ownership} 子仓不得 git submodule update --remote。
"""


def readme(config: dict) -> str:
    java = next(name for name, v in config["services"].items() if v["language"] == "java")
    python = next(name for name, v in config["services"].items() if v["language"] == "python")
    project = config["local"]["project"]
    port = 18080 + config["local"]["port_offset"]
    return f"""# {project}

本项目直接拥有 sdk/java、sdk/python、identity-service、api-gateway 与初始双语业务。没有框架 vendor、template 或私有 SDK 仓库依赖。身份管理页随身份服务；web 为业务页面。第三方依赖需要网络，运行证据由本项目验收记录确认。

## 1. 从创建到初始化

准备 Git、Python 3.12、uv、JDK 21、Node 24.13.0、pnpm 11.12.0、Docker Engine/Compose。以下均在项目根，创建本身未启动容器或安装业务依赖：

```bash
uv sync --frozen
./mvnw.cmd -pl :{java} -am spotless:apply
uv lock --project services/{python}
uv sync --frozen --project services/{python}
uv run --frozen python scripts/init.py --env local --services all
```

Windows 用 mvnw.cmd，Linux/macOS 用 ./mvnw；生成后的首次依赖准备同时格式化改名后的 Java 源码。首次创建才生成业务锁，提交后使用 frozen sync；不要每次启动重新 lock。体验演示时 init 显式加 --with-demo；Linux 加 --dev-host <容器可达宿主私网地址>。Docker Desktop 的 host.docker.internal 需双方可解析；不修改 hosts/防火墙，不用 localhost 注册容器需访问的宿主服务。

init 从本项目构建开发和 Python 迁移镜像，安装/核对组件资源，先发布 Nacos 配置再启动身份，登记技术客户端/发布目录/迁移业务，最后启动入口。失败停止并保留数据，不删卷重试；基础环境 ready 不等于业务启动。配置与端口在 config；秘密/运行绑定在忽略的 .local。

可选 CI/监控见 docs/guides/05-工程检查与监控指南.md；SDK API、日志、任务和错误见 docs/guides/06-SDK使用参考.md；安全验收见 docs/guides/07-系统安全验收清单.md。默认不启用监控。

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

同仓新增服务：在固定兼容脚手架执行 `uv run --frozen python scripts/create_service.py --source local --project-root <本项目目录> --language python --name scoring-service --package scoring --port 48085`，会生成服务并更新登记、路由、IDE和CI矩阵，源码直接提交主仓；完成初始化与显式授权后运行。不会创建远程仓库或复制已有业务。

独立分仓服务在兼容版本脚手架仓库运行 create_service.py --project-root <本项目绝对目录>，从标准样例生成独立目录；项目不保存第二份样例。自己提交/push 服务仓库后，在本项目：

```bash
uv run --frozen python scripts/add_service.py --repo <远程URL> --ref <完整提交> --language python --name scoring-service --package scoring --port 48085
uv run --frozen python scripts/init.py --env local --services scoring-service
```

按输出完成管理员客户端范围/租户绑定和角色授权，再重新登录。在 .github/workflows/checks.yml 的对应语言矩阵登记新服务 module 和定向 tests；漏登记或测试不存在时 CI 会失败。新服务默认没有 peer 调用。初始两业务直接提交主仓；后续 submodule 先 push 子仓再提交主仓 gitlink。另一开发者 git clone --recurse-submodules，独立 local 不需要 A 的 .local；共享环境从受控渠道取回原秘密，不生成替代密码。

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


def service_files(root: Path, language: str, name: str, package: str, peer: str | None) -> None:
    validate_name(name)
    validate_package(package, language)
    target = root / "services" / name
    if target.exists():
        raise ValueError("服务已存在，拒绝覆盖")
    source = FRAMEWORK / "services" / f"sample-{language}-service"
    values = replacements(language, name, package, peer)
    for path in source_files(source):
        relative = path.relative_to(source)
        if not path.is_file():
            continue
        if path.name in {"uv.lock", "uv.toml", "requirements.lock", "Dockerfile"}:
            continue
        text = replace(path.read_text(encoding="utf-8"), values)
        write(target / replace(relative.as_posix(), values), text)
    if language == "java":
        pom = target / "pom.xml"
        text = (
            pom.read_text(encoding="utf-8")
            .replace(
                "<relativePath/>",
                "<relativePath>../../pom.xml</relativePath>",
            )
            .replace("<groupId>com.example</groupId>", f"<groupId>{package}</groupId>")
        )
        write(pom, text)
    from build import business_dockerfile

    write(
        target / "Dockerfile", business_dockerfile(name, {"language": language, "package": package})
    )


def copy_source(source: Path, target: Path) -> None:
    if source.is_symlink():
        raise ValueError("源码导出不接受符号链接")
    if source.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        target.chmod(source.stat().st_mode)
    elif source.is_dir():
        for path in source_files(source):
            relative = path.relative_to(source)
            if path.is_file():
                copy_source(path, target / relative)
    else:
        raise ValueError("快照缺少交付文件：" + str(source))


def generate(args: argparse.Namespace) -> None:
    root = args.root.resolve()
    validate_name(args.project)
    if args.java_service == args.python_service:
        raise ValueError("两服务名称不能相同")
    for language in ("java", "python"):
        name = getattr(args, language + "_service")
        validate_name(name)
        validate_package(getattr(args, language + "_package"), language)
        if len(args.project) + len(name) + 16 > 63:
            raise ValueError("项目与服务名组合超出 PostgreSQL 名称限制")
    if not 0 <= args.port_offset <= 10000:
        raise ValueError("port-offset 必须在 0 到 10000 之间")
    if root.exists() and any(root.iterdir()):
        raise ValueError("生成目标必须为空，不覆盖项目")
    root.mkdir(parents=True, exist_ok=True)
    for name in (
        "sdk",
        ".mvn",
        "pom.xml",
        "mvnw",
        "mvnw.cmd",
        "pyproject.toml",
        "uv.lock",
        ".editorconfig",
        ".gitattributes",
    ):
        copy_source(FRAMEWORK / name, root / name)
    for core_name in ("identity-service", "api-gateway"):
        copy_source(FRAMEWORK / "services" / core_name, root / "services" / core_name)
    # 项目只导出实际需要的运行/开发工具，不先整目录复制再打补丁删除。
    project_tools = (
        "add_service.py",
        "build.py",
        "catalog.py",
        "check_python.py",
        "common.py",
        "compose.py",
        "dev.py",
        "ide.py",
        "init.py",
        "migrate.py",
        "package.py",
        "prepare_local.py",
        "registration.py",
        "config/components.py",
        "config/contract.py",
        "config/publish.py",
        "infra/init.py",
        "infra/up.py",
        "deploy/migrate.py",
        "deploy/activate_certificate.py",
        "deploy/deployment_receipt.py",
        "deploy/sync_permissions.py",
        "deploy/validate_tls.py",
        "deploy/verify_release.py",
    )
    for tool in project_tools:
        copy_source(FRAMEWORK / "scripts" / tool, root / "scripts" / tool)
    for folder in ("config", "infra", "deploy", "web"):
        copy_source(FRAMEWORK / folder, root / folder)
    for path in (root / "config/nacos").glob("sample-*"):
        path.unlink()  # 仅移除刚复制的模板占位配置。
    core = {
        "identity-service": {
            "source": "local",
            "language": "java",
            "directory": "services/identity-service",
            "package": "com.ken.platform.identity",
            "main_class": "com.ken.platform.identity.IdentityApplication",
            "resources": "infra/resources.yaml",
            "migration": {
                "tool": "flyway",
                "path": "services/identity-service/src/main/resources/db/migration",
            },
            "requires": [],
        },
        "api-gateway": {
            "source": "local",
            "language": "java",
            "directory": "services/api-gateway",
            "package": "com.ken.platform.gateway",
            "main_class": "com.ken.platform.gateway.GatewayApplication",
            "requires": ["identity-service"],
        },
    }
    write_yaml(root / "config/services.yaml", {"services": core})
    write_yaml(
        root / "config/development.yaml",
        {"local": {"project": args.project, "port_offset": args.port_offset}, "services": {}},
    )
    gateway_path = root / "config/nacos/api-gateway.yaml"
    gateway = read_yaml(gateway_path)
    gateway["spring"]["cloud"]["gateway"]["server"]["webflux"]["routes"] = []
    write_yaml(gateway_path, gateway)
    pom = root / "pom.xml"
    text = pom.read_text(encoding="utf-8")
    text = text.replace("    <module>services/sample-java-service</module>\n", "")
    write(pom, text)
    for language, port in (("java", 48082), ("python", 48083)):
        name, package = getattr(args, language + "_service"), getattr(args, language + "_package")
        peer = args.python_service if language == "java" else args.java_service
        service_files(root, language, name, package, peer)
        register(root, language, name, package, port + args.port_offset, peer)

        write(
            root / "services" / name / "README.md", service_readme(name, language, package, "local")
        )
    mappings = replacements("java", args.java_service, args.java_package, args.python_service)
    mappings |= replacements("python", args.python_service, args.python_package, args.java_service)
    mappings |= {
        'value="java"': f'value="{args.java_service}"',
        'value="python"': f'value="{args.python_service}"',
        "${service}-items": "${service}/items",
    }
    for path in (root / "web").rglob("*"):
        if path.is_file():
            write(path, replace(path.read_text(encoding="utf-8"), mappings))
    for path in (root / "config").rglob("*.example"):
        write(
            path,
            replace(path.read_text(encoding="utf-8"), mappings).replace(
                "sample-project", args.project
            ),
        )
    write(
        root / ".gitignore",
        ".local/\ndist/\n**/.venv/\n**/target/\n**/__pycache__/\n**/.pytest_cache/\n**/.mypy_cache/\n**/.ruff_cache/\n**/node_modules/\n*.env\n!*.env.example\n",
    )
    write(
        root / ".dockerignore",
        ".git\n.local\ndist\n**/.git\n**/.venv\n**/target\n**/__pycache__\n**/node_modules\n",
    )
    copy_source(FRAMEWORK / "docs/standards", root / "docs/standards")
    for fixture in ("introspection-v1.json", "gateway-routes-v1.json", "api-errors-v1.json"):
        copy_source(FRAMEWORK / "tests/fixtures" / fixture, root / "tests/fixtures" / fixture)
    copy_source(FRAMEWORK / "tests/acceptance/smoke.cjs", root / "tests/acceptance/smoke.cjs")
    write(
        root / "tests/README.md",
        "# 项目测试\n\nSDK 和服务测试随各自源码维护。fixtures 为共享协议数据。\n"
        "acceptance/smoke.cjs 仅用于启用演示数据的 local 环境；会创建演示记录并处理初始改密。\n"
        "先安装 identity-service/admin 的锁定开发依赖和 Playwright Chromium，启动两个业务服务后执行：\n"
        f"node tests/acceptance/smoke.cjs . {args.java_service} {args.python_service}\n"
        "替换样例 API 后同步修改本项目的 smoke；不对 test/prod 执行。\n",
    )
    # 相同流水线交付新项目，仅移除框架维护专用 job。
    for path in (FRAMEWORK / ".github").rglob("*"):
        if path.is_file() and path.name != "scaffold-release-check.yml":
            copy_source(path, root / path.relative_to(FRAMEWORK))
    checks_path = root / ".github/workflows/checks.yml"
    write(
        checks_path,
        checks_path.read_text(encoding="utf-8")
        .replace("sample-java-service", args.java_service)
        .replace("sample-python-service", args.python_service),
    )
    checks = read_yaml(checks_path)
    checks["jobs"].pop("maintenance", None)
    write_yaml(root / ".github/workflows/checks.yml", checks)
    # 项目操作说明保留，但不带框架设计/历史计划。
    monitor = (FRAMEWORK / "docs/guides/05-工程检查与监控指南.md").read_text(encoding="utf-8")
    monitor = monitor.replace(
        "权限和指标边界见[部署设计](../design/09-应用部署与运维设计.md#observability)。",
        "权限和指标边界按本项目维护的说明核对。",
    )
    monitor = monitor.replace(
        "`create.py --ref` 只导出固定提交；请选择包含所需能力且已核对的提交，不会导出工作区未提交变更。",
        "本项目由固定源码提交生成；实际验证记录由项目维护。",
    )
    monitor = monitor.split("## 最小源码发行验收（D-20261029-01）", 1)[0].rstrip() + "\n"
    write(root / "docs/guides/05-工程检查与监控指南.md", monitor)
    for guide in (
        "06-SDK使用参考.md",
        "07-系统安全验收清单.md",
        "08-管理后台功能与身份接口清单.md",
    ):
        text = (FRAMEWORK / "docs/guides" / guide).read_text(encoding="utf-8")
        text = text.replace("sample-java-service", args.java_service).replace(
            "sample-python-service", args.python_service
        )
        text = text.replace("sample_service", args.python_package).replace(
            "com.example.sample", args.java_package
        )
        text = text.replace(
            "[公共HTTP合同](../design/07-Java与Python协作设计.md#http)", "本项目公共HTTP合同"
        )
        text = re.sub(r"\[([^\]]+)\]\(\.\./(?:design|plans)/[^)]+\)", r"\1", text)
        write(root / "docs/guides" / guide, text)
    # 项目只带使用说明和规范，不带框架设计/历史计划。
    for path in (root / "docs/standards").glob("*.md"):
        lines = path.read_text(encoding="utf-8").splitlines()
        write(path, "\n".join(line for line in lines if not line.startswith("目录同步：")) + "\n")
    rules = """# AGENTS.md

默认中文沟通；保留现有修改，先明确范围与验证，仅运行受影响测试，秘密不入库。
写代码前必读[共同规范](docs/standards/01-项目编码规范.md)，按语言读取
[Java 规范](docs/standards/02-Java编码规范.md)和[Python 规范](docs/standards/03-Python编码规范.md)。
业务在 services，公共源码在 sdk；local服务直接提交主仓；独立服务使用固定submodule，先提交子仓再提交主仓gitlink。
Java 使用 JDK 21；在项目根对受影响模块运行 `./mvnw -pl <module> -am spotless:check` 和定向测试。
Python 使用各模块自己的 uv 环境；从根执行 `uv run --frozen python scripts/check_python.py --paths <files>`，
在受影响模块执行 `uv run --frozen mypy src` 和定向 pytest。不得把格式检查当作权限或事务验收。
交付说明所读规范、实际验证命令、结果和未验证项。
"""
    write(root / "AGENTS.md", rules)
    from catalog import project_config
    from ide import install

    config = project_config(root)
    write(
        root / "README.md",
        readme(config) + f"\n模板来源提交：{getattr(args, 'source_ref', 'unpublished-test')}。\n",
    )
    write(root / "docs/guides/04-新项目开发与规范升级指南.md", readme(config))
    write(
        root / "scripts/README.md",
        "# 项目脚本\n\n从项目根运行；使用根 uv.lock。\n\n"
        "操作步骤见[开发与部署指南](../docs/guides/04-新项目开发与规范升级指南.md)。"
        "init 准备本地依赖，dev 启动业务，compose 管理本地基础容器；"
        "add_service 接入固定子仓，migrate 执行数据库迁移，build/package 生成发行产物。\n",
    )
    install(root, config["services"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in (
        "project",
        "java-service",
        "java-package",
        "python-service",
        "python-package",
        "source-ref",
    ):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--port-offset", type=int, default=0)
    generate(parser.parse_args())
