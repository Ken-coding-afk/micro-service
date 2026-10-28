import tomllib
from importlib.metadata import version
from pathlib import Path

from ken_platform_sdk import __version__


def test_runtime_version_matches_distribution_and_source_metadata():
    project = Path(__file__).resolve().parents[1] / "pyproject.toml"
    expected = tomllib.loads(project.read_text(encoding="utf-8"))["project"]["version"]
    assert __version__ == version("ken-platform-sdk") == expected
