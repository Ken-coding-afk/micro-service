package com.example.sample.dal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ItemRepository {
  private final JdbcTemplate jdbc;

  public ItemRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void create(UUID id, UUID tenant, UUID owner, String name) {
    jdbc.update(
        "INSERT INTO sample_item(id,tenant_id,owner_user_id,name) VALUES(?,?,?,?)",
        id,
        tenant,
        owner,
        name);
  }

  public Map<String, Object> list(UUID tenant, UUID owner, boolean own, int page, int size) {
    String predicate = "tenant_id=? AND (NOT ? OR owner_user_id=?)";
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM sample_item WHERE " + predicate, Long.class, tenant, own, owner);
    List<Map<String, Object>> items =
        jdbc.query(
            "SELECT id,name FROM sample_item WHERE " + predicate + " ORDER BY id LIMIT ? OFFSET ?",
            (row, n) ->
                Map.<String, Object>of("id", row.getString("id"), "name", row.getString("name")),
            tenant,
            own,
            owner,
            size,
            ((long) page - 1) * size);
    return Map.of("items", items, "total", count, "page", page, "page_size", size);
  }
}
