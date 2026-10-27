package com.ken.platform.foundation.data;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/** PG UUID 显式适配，含 foreach 中可推断为 UUID 的参数，禁止退化成字符串拼 SQL。 */
@MappedTypes(UUID.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public final class UuidTypeHandler extends BaseTypeHandler<UUID> {
  @Override
  public void setNonNullParameter(
      PreparedStatement statement, int index, UUID value, JdbcType jdbcType) throws SQLException {
    statement.setObject(index, value, Types.OTHER);
  }

  @Override
  public UUID getNullableResult(ResultSet result, String column) throws SQLException {
    return result.getObject(column, UUID.class);
  }

  @Override
  public UUID getNullableResult(ResultSet result, int column) throws SQLException {
    return result.getObject(column, UUID.class);
  }

  @Override
  public UUID getNullableResult(CallableStatement result, int column) throws SQLException {
    return result.getObject(column, UUID.class);
  }
}
