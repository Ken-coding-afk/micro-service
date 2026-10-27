package com.ken.platform.identity.dal.mapper.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditQueryMapper {
  record Filter(
      Instant from,
      Instant to,
      UUID actor,
      String action,
      String target,
      String result,
      int offset,
      int limit) {}

  record Row(
      UUID id,
      UUID actorId,
      String actorType,
      UUID tenantId,
      String action,
      String targetId,
      String result,
      String requestId,
      String details,
      Instant createdAt) {}

  String FIELDS =
      "id,actor_id AS \"actorId\",actor_type AS \"actorType\",tenant_id AS \"tenantId\",action,target_id AS \"targetId\",result,request_id AS \"requestId\",left(details::text,16385) AS details,created_at AS \"createdAt\"";
  String FILTERS =
      """
      AND (CAST(#{filter.from,jdbcType=TIMESTAMP} AS timestamptz) IS NULL OR created_at>=#{filter.from,jdbcType=TIMESTAMP})
      AND (CAST(#{filter.to,jdbcType=TIMESTAMP} AS timestamptz) IS NULL OR created_at<#{filter.to,jdbcType=TIMESTAMP})
      AND (CAST(#{filter.actor,jdbcType=OTHER} AS uuid) IS NULL OR actor_id=#{filter.actor,jdbcType=OTHER})
      AND (CAST(#{filter.action,jdbcType=VARCHAR} AS text) IS NULL OR action=#{filter.action,jdbcType=VARCHAR})
      AND (CAST(#{filter.target,jdbcType=VARCHAR} AS text) IS NULL OR target_id=#{filter.target,jdbcType=VARCHAR})
      AND (CAST(#{filter.result,jdbcType=VARCHAR} AS text) IS NULL OR result=#{filter.result,jdbcType=VARCHAR})
      ORDER BY created_at DESC,id DESC LIMIT #{filter.limit} OFFSET #{filter.offset}
      """;

  @Select("SELECT " + FIELDS + " FROM sys_auth_audit WHERE tenant_id=#{tenant} " + FILTERS)
  List<Row> tenant(@Param("tenant") UUID tenant, @Param("filter") Filter filter);

  @Select("SELECT " + FIELDS + " FROM sys_auth_audit WHERE actor_type='PLATFORM' " + FILTERS)
  List<Row> platform(@Param("filter") Filter filter);
}
