"""共享 VS Code 配置；按稳定名称追加，不能覆盖个人调试配置。"""

from __future__ import annotations

import json
from pathlib import Path

from common import write


def entries(name: str, record: dict) -> tuple[dict, dict]:
    label = "service:" + name
    task = {
        "label": label + ":prepare",
        "type": "process",
        "command": "uv",
        "args": [
            "run",
            "--frozen",
            "python",
            "scripts/dev.py",
            name,
            "--prepare-only",
            "--tenant-code",
            "${input:swaggerTenant}",
        ],
        "options": {"cwd": "${workspaceFolder}"},
        "problemMatcher": [],
    }
    launch = {
        "name": label,
        "request": "launch",
        "preLaunchTask": task["label"],
        "envFile": "${workspaceFolder}/.local/runtime/" + name + "-ide.env",
    }
    if record["language"] == "java":
        launch |= {
            "type": "java",
            "mainClass": record["main_class"],
            "projectName": name,
            "cwd": "${workspaceFolder}/services/" + name,
            "console": "integratedTerminal",
        }
    else:
        folder = "${workspaceFolder}/services/" + name
        launch |= {
            "type": "debugpy",
            "module": record["debug_module"],
            "cwd": folder,
            "python": folder + "/.venv/bin/python",
            "justMyCode": False,
            "windows": {"python": folder + "/.venv/Scripts/python.exe"},
            "console": "integratedTerminal",
        }
    return task, launch


def install(root: Path, records: dict) -> None:
    tasks, launches = [], []
    for label, command in {
        "project:init": ["scripts/init.py", "--env", "local", "--services", "all"],
        "project:status": ["scripts/compose.py", "ps"],
        "project:logs": ["scripts/compose.py", "logs"],
        "project:stop": ["scripts/compose.py", "stop"],
    }.items():
        tasks.append(
            {
                "label": label,
                "type": "process",
                "command": "uv",
                "args": ["run", "--frozen", "python", *command],
                "options": {"cwd": "${workspaceFolder}"},
                "problemMatcher": [],
            }
        )
    for name, record in records.items():
        task, launch = entries(name, record)
        tasks.append(task)
        launches.append(launch)
        tasks.append(
            {
                **task,
                "label": "service:" + name + ":run",
                "args": ["run", "--frozen", "python", "scripts/dev.py", name],
            }
        )
        if record["language"] == "python":
            tasks.append(
                {
                    **task,
                    "label": "service:" + name + ":reload",
                    "args": ["run", "--frozen", "python", "scripts/dev.py", name, "--reload"],
                }
            )
    additions = {
        "tasks.json": (
            "tasks",
            "label",
            {
                "version": "2.0.0",
                "tasks": tasks,
                "inputs": [
                    {
                        "id": "swaggerTenant",
                        "type": "promptString",
                        "description": "Swagger 租户编码（不用 Swagger 可留空）",
                        "default": "",
                    }
                ],
            },
        ),
        "launch.json": (
            "configurations",
            "name",
            {
                "version": "0.2.0",
                "configurations": launches,
                "compounds": [
                    {
                        "name": "services:combined",
                        "configurations": [v["name"] for v in launches],
                        "stopAll": True,
                    }
                ],
            },
        ),
    }
    pending = {}
    for filename, (field, key, proposed) in additions.items():
        path = root / ".vscode" / filename
        if path.exists():
            try:
                existing = json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError as error:
                candidate = root / ".local/ide-candidates" / filename
                write(candidate, json.dumps(proposed, ensure_ascii=False, indent=2) + "\n")
                raise ValueError(
                    f"保留已有 JSONC 注释/格式，请人工合并候选：{candidate}"
                ) from error
            known = {entry[key] for entry in existing.get(field, [])}
            # 同名已有配置原样保留；登记服务名冲突在 add_service 前置检查。
            existing.setdefault(field, []).extend(v for v in proposed[field] if v[key] not in known)
            if field == "tasks":
                known_inputs = {entry["id"] for entry in existing.get("inputs", [])}
                existing.setdefault("inputs", []).extend(
                    v for v in proposed.get("inputs", []) if v["id"] not in known_inputs
                )
            proposed = existing
        pending[path] = json.dumps(proposed, ensure_ascii=False, indent=2) + "\n"
    for path, value in pending.items():
        write(path, value)
    for filename, value in {
        "extensions.json": {
            "recommendations": ["vscjava.vscode-java-pack", "ms-python.python", "ms-python.debugpy"]
        },
        "settings.json": {"java.configuration.updateBuildConfiguration": "interactive"},
    }.items():
        path = root / ".vscode" / filename
        if not path.exists():
            write(path, json.dumps(value, indent=2) + "\n")
