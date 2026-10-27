import json
import uuid
from pathlib import Path

import pytest

from ken_platform_sdk.context import request_context
from ken_platform_sdk.errors import PlatformError
from ken_platform_sdk.storage import ObjectRecord, TenantResources, presign_seconds

TENANT = uuid.UUID("11111111-1111-4111-8111-111111111111")
ACTOR = uuid.uuid4()
RESOURCES = TenantResources("doc-insight", "local", "document-service")


def test_shared_vectors_match_java_and_partition_all_resource_dimensions():
    fixture = (
        Path(__file__).resolve().parents[3]
        / "sdk/java/src/test/resources/tenant-resource-vectors.json"
    )
    with request_context(tenant_id=TENANT):
        for vector in json.loads(fixture.read_text(encoding="utf-8")):
            assert RESOURCES.cache_key("document", vector["business_key"]) == vector["cache_key"]
        base = RESOURCES.cache_key("document", "a:b")
        variants = [
            RESOURCES.cache_key("document", "a/b"),
            TenantResources("doc-insight", "test", "document-service").cache_key("document", "a:b"),
            TenantResources("other-project", "local", "document-service").cache_key(
                "document", "a:b"
            ),
            TenantResources("doc-insight", "local", "analysis-service").cache_key(
                "document", "a:b"
            ),
        ]
        assert base not in variants
    with request_context(tenant_id=uuid.uuid4()):
        assert RESOURCES.cache_key("document", "a:b") != base


def test_missing_tenant_never_becomes_global_access():
    for operation in [
        lambda: RESOURCES.cache_key("document", "42"),
        lambda: RESOURCES.new_object_key("document"),
    ]:
        with pytest.raises(PlatformError) as error:
            operation()
        assert error.value.code == "TENANT_CONTEXT_REQUIRED"
    with request_context(principal_id=str(ACTOR)), pytest.raises(PlatformError):
        RESOURCES.new_object_key("document")


@pytest.mark.parametrize("unsafe", ["../other", "a/b", "a:b", "%2f", "", "{hash}"])
def test_untrusted_resource_paths_and_purposes_are_rejected(unsafe):
    with request_context(tenant_id=TENANT):
        with pytest.raises(PlatformError):
            RESOURCES.new_object_key(unsafe)
        with pytest.raises(PlatformError):
            RESOURCES.cache_key(unsafe, "42")


@pytest.mark.parametrize("unsafe", ["", "a\n", "中" * 200, "x" * 513, "\ud800"])
def test_cache_business_keys_cannot_inject_control_or_invalid_bytes(unsafe):
    with request_context(tenant_id=TENANT), pytest.raises(PlatformError):
        RESOURCES.cache_key("document", unsafe)


def test_objects_require_db_tenant_and_effective_self_or_tenant_scope():
    object_id = uuid.uuid4()
    own = ObjectRecord(TENANT, "document", object_id, ACTOR)
    expected = f"doc-insight/local/{TENANT}/document/{object_id}"
    with request_context(tenant_id=TENANT, principal_id=str(ACTOR)):
        assert RESOURCES.authorized_object_key(own, "SELF") == expected
        assert RESOURCES.authorized_object_key(own, "TENANT") == expected
        with pytest.raises(PlatformError) as missing:
            RESOURCES.authorized_object_key(own, None)
        assert missing.value.status == 403
        other = ObjectRecord(TENANT, "document", object_id, uuid.uuid4())
        with pytest.raises(PlatformError):
            RESOURCES.authorized_object_key(other, "SELF")
        assert RESOURCES.authorized_object_key(other, "TENANT") == expected
        for foreign in [ObjectRecord(uuid.uuid4(), "document", object_id, ACTOR), None]:
            with pytest.raises(PlatformError) as hidden:
                RESOURCES.authorized_object_key(foreign, "TENANT")
            assert hidden.value.code == "OBJECT_NOT_FOUND"
            assert hidden.value.status == 404
        with pytest.raises(PlatformError):
            RESOURCES.authorized_object_key(
                ObjectRecord(TENANT, "document", object_id, None), "SELF"
            )


def test_new_objects_use_random_uuid_and_signatures_cannot_exceed_five_minutes():
    with request_context(tenant_id=TENANT):
        first = RESOURCES.new_object_key("document")
        second = RESOURCES.new_object_key("document")
        assert first.startswith(f"doc-insight/local/{TENANT}/document/")
        assert first != second
        assert uuid.UUID(first.rsplit("/", 1)[1]).version == 4
    assert presign_seconds() == 300
    assert presign_seconds(1) == 1
    for invalid in [0, -1, 301, 2**63, True, 3.5]:
        with pytest.raises(PlatformError):
            presign_seconds(invalid)
