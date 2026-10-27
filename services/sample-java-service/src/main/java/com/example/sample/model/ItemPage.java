package com.example.sample.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ItemPage(
    @JsonProperty(required = true) List<Item> items,
    @JsonProperty(required = true) long total,
    @JsonProperty(required = true) int page,
    @JsonProperty(value = "page_size", required = true) int pageSize) {
  public ItemPage {
    items = List.copyOf(items);
  }
}
