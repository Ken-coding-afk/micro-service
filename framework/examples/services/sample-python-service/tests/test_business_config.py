import pytest
from pydantic import ValidationError

from sample_service.config import BusinessSettings


def test_business_model_owns_its_limits_and_rejects_invalid_or_unknown_values():
    assert BusinessSettings().max_page_size == 100
    assert BusinessSettings(max_page_size=50).max_page_size == 50
    for values in [
        {"max_page_size": "50"},
        {"max_page_size": 19},
        {"max_page_size": 101},
        {"max_page_size": True},
        {"other": 1},
    ]:
        with pytest.raises(ValidationError):
            BusinessSettings.model_validate(values)
