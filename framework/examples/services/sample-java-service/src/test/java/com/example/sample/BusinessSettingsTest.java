package com.example.sample;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BusinessSettingsTest {
  @Test
  void businessOwnsLimitsAndRejectsUnknownOrInvalidConfig() {
    assertThat(BusinessSettings.from(Map.of()).maxPageSize()).isEqualTo(100);
    assertThat(BusinessSettings.from(Map.of("max_page_size", 50)).maxPageSize()).isEqualTo(50);
    for (var value : new Object[] {"50", 19, 101, true}) {
      assertThatThrownBy(() -> BusinessSettings.from(Map.of("max_page_size", value)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> BusinessSettings.from(Map.of("other", 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
