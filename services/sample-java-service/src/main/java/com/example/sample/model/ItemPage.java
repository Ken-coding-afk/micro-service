package com.example.sample.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ItemPage(
    @JsonProperty(required = true) List<Item> items,
    @JsonProperty(required = true) long total,
    @JsonProperty(required = true) int page,
    @JsonProperty(required = true) int size,
    @JsonProperty(value = "has_more", required = true) boolean hasMore) {
  public ItemPage {
    items = List.copyOf(items);
  }
}
