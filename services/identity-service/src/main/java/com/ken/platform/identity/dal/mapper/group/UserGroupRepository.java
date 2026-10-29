package com.ken.platform.identity.dal.mapper.group;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserGroupRepository {
  public record Group(
      UUID id,
      @JsonProperty("parent_id") UUID parentId,
      String code,
      String name,
      String description,
      int sort,
      String status,
      long version,
      @JsonProperty("effective_status") String effectiveStatus,
      @JsonProperty("group_path") String groupPath,
      @JsonProperty("organization_version") long organizationVersion,
      @JsonProperty("has_children") boolean hasChildren,
      @JsonProperty("member_count") long memberCount,
      @JsonProperty("subtree_member_count") long subtreeMemberCount,
      @JsonProperty("descendant_count") long descendantCount,
      @JsonProperty("role_count") long roleCount) {}

  public record Member(
      UUID id,
      String username,
      @JsonProperty("display_name") String displayName,
      String status,
      long version,
      boolean direct,
      @JsonProperty("group_paths") List<String> groupPaths) {}

  public record Role(
      UUID id,
      String code,
      String name,
      String status,
      long version,
      @JsonProperty("protected") boolean protectedRole) {}

  private static final String TREE =
      """
      WITH RECURSIVE tree AS (
        SELECT g.*,ARRAY[g.id] AS path_ids,g.name::text AS group_path,g.status='ACTIVE' AS effective
        FROM sys_user_group g WHERE g.tenant_id=:tenant AND g.parent_id IS NULL
        UNION ALL
        SELECT g.*,p.path_ids || g.id,p.group_path || ' / ' || g.name,p.effective AND g.status='ACTIVE'
        FROM sys_user_group g JOIN tree p ON p.tenant_id=g.tenant_id AND p.id=g.parent_id
        WHERE g.tenant_id=:tenant
      )
      """;
  private static final String GROUPS =
      TREE
          + """
      SELECT t.*,s.organization_version,
        EXISTS(SELECT 1 FROM tree c WHERE c.parent_id=t.id) AS has_children,
        (SELECT count(*) FROM sys_user_group_member m WHERE m.tenant_id=t.tenant_id AND m.group_id=t.id) AS member_count,
        (SELECT count(DISTINCT m.user_id) FROM sys_user_group_member m JOIN tree c ON c.id=m.group_id AND c.tenant_id=m.tenant_id
          WHERE m.tenant_id=t.tenant_id AND t.id=ANY(c.path_ids)) AS subtree_member_count,
        (SELECT count(*) FROM tree c WHERE t.id=ANY(c.path_ids) AND c.id<>t.id) AS descendant_count,
        (SELECT count(*) FROM sys_user_group_role r WHERE r.tenant_id=t.tenant_id AND r.group_id=t.id) AS role_count
      FROM tree t JOIN sys_tenant s ON s.id=t.tenant_id
      """;
  private final NamedParameterJdbcTemplate jdbc;

  public UserGroupRepository(JdbcTemplate jdbc) {
    this.jdbc = new NamedParameterJdbcTemplate(jdbc);
  }

  private static MapSqlParameterSource params(UUID tenant) {
    return new MapSqlParameterSource("tenant", tenant);
  }

  public long organizationVersion(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT organization_version FROM sys_tenant WHERE id=:tenant", params(tenant), Long.class);
  }

  public List<Group> list(UUID tenant, UUID parent, String query, int offset, int limit) {
    return jdbc.query(
        GROUPS
            + """
        WHERE (:query<>'' AND position(lower(:query) in lower(t.code || ' ' || t.group_path))>0)
           OR (:query='' AND t.parent_id IS NOT DISTINCT FROM CAST(:parent AS uuid))
        ORDER BY t.sort,t.name,t.id LIMIT :limit OFFSET :offset
        """,
        params(tenant)
            .addValue("parent", parent)
            .addValue("query", query)
            .addValue("limit", limit)
            .addValue("offset", offset),
        UserGroupRepository::group);
  }

  public Group get(UUID tenant, UUID id) {
    return jdbc
        .query(
            GROUPS + " WHERE t.id=:id",
            params(tenant).addValue("id", id),
            UserGroupRepository::group)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<Group> userGroups(UUID tenant, UUID user) {
    return jdbc.query(
        GROUPS
            + " WHERE EXISTS(SELECT 1 FROM sys_user_group_member m WHERE m.tenant_id=t.tenant_id AND m.group_id=t.id AND m.user_id=:user) ORDER BY t.group_path,t.id",
        params(tenant).addValue("user", user),
        UserGroupRepository::group);
  }

  public List<Group> roleGroups(UUID tenant, UUID role, int offset, int limit) {
    return jdbc.query(
        GROUPS
            + " WHERE EXISTS(SELECT 1 FROM sys_user_group_role r WHERE r.tenant_id=t.tenant_id AND r.group_id=t.id AND r.role_id=:role) ORDER BY t.group_path,t.id LIMIT :limit OFFSET :offset",
        params(tenant).addValue("role", role).addValue("offset", offset).addValue("limit", limit),
        UserGroupRepository::group);
  }

  public boolean descendant(UUID tenant, UUID parent, UUID child) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            TREE + " SELECT EXISTS(SELECT 1 FROM tree WHERE id=:child AND :parent=ANY(path_ids))",
            params(tenant).addValue("child", child).addValue("parent", parent),
            Boolean.class));
  }

  public void lock(UUID tenant, List<UUID> ids) {
    if (!ids.isEmpty())
      jdbc.queryForList(
          "SELECT id FROM sys_user_group WHERE tenant_id=:tenant AND id IN (:ids) ORDER BY id FOR UPDATE",
          params(tenant).addValue("ids", ids),
          UUID.class);
  }

  public void create(
      UUID tenant, UUID id, UUID parent, String code, String name, String description, int sort) {
    jdbc.update(
        "INSERT INTO sys_user_group(id,tenant_id,parent_id,code,name,description,sort) VALUES(:id,:tenant,:parent,:code,:name,:description,:sort)",
        params(tenant)
            .addValue("id", id)
            .addValue("parent", parent)
            .addValue("code", code)
            .addValue("name", name)
            .addValue("description", description)
            .addValue("sort", sort));
  }

  public void update(
      UUID tenant, UUID id, String name, String description, int sort, String status) {
    jdbc.update(
        "UPDATE sys_user_group SET name=:name,description=:description,sort=:sort,status=:status,version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:id",
        params(tenant)
            .addValue("id", id)
            .addValue("name", name)
            .addValue("description", description)
            .addValue("sort", sort)
            .addValue("status", status));
  }

  public void move(UUID tenant, UUID id, UUID parent, int sort) {
    jdbc.update(
        "UPDATE sys_user_group SET parent_id=:parent,sort=:sort,version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:id",
        params(tenant).addValue("id", id).addValue("parent", parent).addValue("sort", sort));
  }

  public void delete(UUID tenant, UUID id) {
    jdbc.update(
        "DELETE FROM sys_user_group WHERE tenant_id=:tenant AND id=:id",
        params(tenant).addValue("id", id));
  }

  public void advanceTree(UUID tenant) {
    jdbc.update(
        "UPDATE sys_tenant SET organization_version=organization_version+1 WHERE id=:tenant",
        params(tenant));
  }

  public void touch(UUID tenant, UUID group) {
    jdbc.update(
        "UPDATE sys_user_group SET version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:id",
        params(tenant).addValue("id", group));
  }

  public List<Member> members(
      UUID tenant, UUID group, boolean descendants, String query, int offset, int limit) {
    return jdbc.query(
        TREE
            + """
        SELECT u.id,u.normalized_username,u.display_name,u.status,u.version,
          bool_or(m.group_id=:group) AS direct,array_agg(DISTINCT t.group_path ORDER BY t.group_path) AS group_paths
        FROM sys_user u JOIN sys_user_group_member m ON m.tenant_id=u.tenant_id AND m.user_id=u.id
        JOIN tree t ON t.tenant_id=m.tenant_id AND t.id=m.group_id
        WHERE u.tenant_id=:tenant AND (m.group_id=:group OR (:descendants AND :group=ANY(t.path_ids)))
          AND (:query='' OR position(lower(:query) in lower(u.normalized_username || ' ' || u.display_name))>0)
        GROUP BY u.id ORDER BY u.normalized_username,u.id LIMIT :limit OFFSET :offset
        """,
        params(tenant)
            .addValue("group", group)
            .addValue("descendants", descendants)
            .addValue("query", query)
            .addValue("offset", offset)
            .addValue("limit", limit),
        (rs, n) ->
            new Member(
                rs.getObject("id", UUID.class),
                rs.getString("normalized_username"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getLong("version"),
                rs.getBoolean("direct"),
                List.of((String[]) rs.getArray("group_paths").getArray())));
  }

  public boolean member(UUID tenant, UUID group, UUID user) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sys_user_group_member WHERE tenant_id=:tenant AND group_id=:group AND user_id=:user)",
            params(tenant).addValue("group", group).addValue("user", user),
            Boolean.class));
  }

  public void membership(UUID tenant, UUID group, UUID user, boolean add) {
    var p = params(tenant).addValue("group", group).addValue("user", user);
    jdbc.update(
        add
            ? "INSERT INTO sys_user_group_member(tenant_id,group_id,user_id) VALUES(:tenant,:group,:user)"
            : "DELETE FROM sys_user_group_member WHERE tenant_id=:tenant AND group_id=:group AND user_id=:user",
        p);
    jdbc.update(
        "UPDATE sys_user SET version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:user",
        p);
  }

  public List<Role> roles(UUID tenant, UUID group) {
    return jdbc.query(
        "SELECT r.* FROM sys_role r JOIN sys_user_group_role g ON g.tenant_id=r.tenant_id AND g.role_id=r.id WHERE r.tenant_id=:tenant AND g.group_id=:group ORDER BY r.code,r.id",
        params(tenant).addValue("group", group),
        UserGroupRepository::role);
  }

  public List<Role> lockRoles(UUID tenant, List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    return jdbc.query(
        "SELECT * FROM sys_role WHERE tenant_id=:tenant AND id IN (:ids) ORDER BY id FOR UPDATE",
        params(tenant).addValue("ids", ids),
        UserGroupRepository::role);
  }

  public void roleAssignment(UUID tenant, UUID group, UUID role, boolean add) {
    var p = params(tenant).addValue("group", group).addValue("role", role);
    jdbc.update(
        add
            ? "INSERT INTO sys_user_group_role(tenant_id,group_id,role_id) VALUES(:tenant,:group,:role)"
            : "DELETE FROM sys_user_group_role WHERE tenant_id=:tenant AND group_id=:group AND role_id=:role",
        p);
    jdbc.update(
        "UPDATE sys_role SET version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:role",
        p);
  }

  private static Role role(ResultSet rs, int row) throws SQLException {
    return new Role(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("status"),
        rs.getLong("version"),
        rs.getBoolean("protected"));
  }

  private static Group group(ResultSet rs, int row) throws SQLException {
    return new Group(
        rs.getObject("id", UUID.class),
        rs.getObject("parent_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getInt("sort"),
        rs.getString("status"),
        rs.getLong("version"),
        rs.getBoolean("effective") ? "ACTIVE" : "DISABLED",
        rs.getString("group_path"),
        rs.getLong("organization_version"),
        rs.getBoolean("has_children"),
        rs.getLong("member_count"),
        rs.getLong("subtree_member_count"),
        rs.getLong("descendant_count"),
        rs.getLong("role_count"));
  }
}
