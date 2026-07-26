package com.mindsafe.domain.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.*;

/**
 * MyBatis String ↔ PostgreSQL JSONB 类型处理器
 * <p>
 * 解决 MyBatis-Plus 默认以 varchar 传参导致 PostgreSQL 拒绝
 * "column is of type jsonb but expression is of type character varying" 的问题。
 * 写入时通过 PGobject(type=jsonb) 正确设置参数类型；读取时直接返回字符串。
 */
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject pgObject = new PGobject();
        pgObject.setType("jsonb");
        pgObject.setValue(parameter);
        ps.setObject(i, pgObject);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
