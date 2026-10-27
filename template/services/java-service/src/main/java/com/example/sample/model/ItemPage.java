package com.example.sample.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ItemPage(
    List<Item> items, long total, int page, @JsonProperty("page_size") int pageSize) {
  public ItemPage {
    items = List.copyOf(items);
  }
}
