from pathlib import Path

import pytest
from tools.config.components import component_issues, sentinel_rules
from tools.config.contract import validate_nacos_source


def test_mixed_example_sources_include_native_routes_and_both_required_json_files(
    tmp_path,
):
    root = Path(__file__).resolve().parents[2]
    import shutil

    for folder in (root / "config/nacos",):
        for path in folder.iterdir():
            shutil.copyfile(path, tmp_path / path.name)
    assert validate_nacos_source(tmp_path) == []
    (tmp_path / "sample-java-service-sentinel-flow.json").unlink()
    assert any(
        "缺少必需 Sentinel flow" in issue for issue in validate_nacos_source(tmp_path)
    )


@pytest.mark.parametrize(
    "text",
    [
        "",
        "null",
        "{}",
        '[{"resource":"http.inbound","grade":0,"count":0}]',
        '[{"resource":"http.inbound","grade":0,"count":2,"clusterMode":true}]',
        '[{"resource":"http.inbound","grade":0,"count":1,"count":2}]',
    ],
)
def test_invalid_rules_are_not_silent_empty_replacements(text):
    with pytest.raises((ValueError, AssertionError, KeyError, TypeError)):
        sentinel_rules(text, "flow")


def test_valid_clear_and_native_ownership_are_explicit():
    assert sentinel_rules("[]", "degrade") == []
    assert (
        component_issues(
            {"spring": {"cloud": {"sentinel": {"enabled": True}}}},
            "sample-java-service.yaml",
        )
        == []
    )
    assert component_issues(
        {"spring": {"cloud": {"sentinel": {"enabled": True}}}}, "api-gateway.yaml"
    )
    assert component_issues(
        {"concurrency": {"inbound_limit": False}}, "sample-python-service.yaml"
    )
