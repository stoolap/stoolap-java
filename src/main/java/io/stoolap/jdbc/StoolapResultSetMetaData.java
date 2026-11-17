/*
 * Copyright 2025 Stoolap Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stoolap.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * ResultSetMetaData for Stoolap result sets. A column's type is that of its first non-null value in
 * the first chunk of the result; a column with no such value reports {@link Types#NULL}.
 */
public class StoolapResultSetMetaData implements ResultSetMetaData {

  private final String[] columnNames;
  private final byte[] tags;

  StoolapResultSetMetaData(String[] columnNames, byte[] tags) {
    this.columnNames = columnNames;
    this.tags = tags;
  }

  private int tag(int column) throws SQLException {
    if (column < 1 || column > columnNames.length) {
      throw new SQLException("Column index out of range: " + column, "22023");
    }
    return tags[column - 1];
  }

  /** The java.sql.Types code for a packed-format type tag. */
  static int sqlType(int tag) {
    return switch (tag) {
      case 1 -> Types.BIGINT;
      case 2 -> Types.DOUBLE;
      case 3 -> Types.VARCHAR;
      case 4 -> Types.BOOLEAN;
      case 5 -> Types.TIMESTAMP;
      case 6, 7 -> Types.OTHER;
      default -> Types.NULL;
    };
  }

  /** The Stoolap type name for a packed-format type tag. */
  static String typeName(int tag) {
    return switch (tag) {
      case 1 -> "INTEGER";
      case 2 -> "FLOAT";
      case 3 -> "TEXT";
      case 4 -> "BOOLEAN";
      case 5 -> "TIMESTAMP";
      case 6 -> "JSON";
      case 7 -> "VECTOR";
      default -> "NULL";
    };
  }

  @Override
  public int getColumnCount() throws SQLException {
    return columnNames.length;
  }

  @Override
  public String getColumnLabel(int column) throws SQLException {
    tag(column);
    return columnNames[column - 1];
  }

  @Override
  public String getColumnName(int column) throws SQLException {
    return getColumnLabel(column);
  }

  @Override
  public int getColumnType(int column) throws SQLException {
    return sqlType(tag(column));
  }

  @Override
  public String getColumnTypeName(int column) throws SQLException {
    return typeName(tag(column));
  }

  @Override
  public String getColumnClassName(int column) throws SQLException {
    return switch (tag(column)) {
      case 1 -> Long.class.getName();
      case 2 -> Double.class.getName();
      case 3, 6 -> String.class.getName();
      case 4 -> Boolean.class.getName();
      case 5 -> java.sql.Timestamp.class.getName();
      case 7 -> float[].class.getName();
      default -> Object.class.getName();
    };
  }

  @Override
  public boolean isAutoIncrement(int column) throws SQLException {
    return false;
  }

  @Override
  public boolean isCaseSensitive(int column) throws SQLException {
    int t = tag(column);
    return t == 3 || t == 6;
  }

  @Override
  public boolean isSearchable(int column) throws SQLException {
    return true;
  }

  @Override
  public boolean isCurrency(int column) throws SQLException {
    return false;
  }

  @Override
  public int isNullable(int column) throws SQLException {
    tag(column);
    return columnNullableUnknown;
  }

  @Override
  public boolean isSigned(int column) throws SQLException {
    int t = tag(column);
    return t == 1 || t == 2;
  }

  @Override
  public int getColumnDisplaySize(int column) throws SQLException {
    return switch (tag(column)) {
      case 1 -> 20;
      case 2 -> 24;
      case 4 -> 5;
      case 5 -> 35;
      default -> Integer.MAX_VALUE;
    };
  }

  @Override
  public String getSchemaName(int column) throws SQLException {
    return "";
  }

  @Override
  public int getPrecision(int column) throws SQLException {
    return switch (tag(column)) {
      case 1 -> 19;
      case 2 -> 17;
      case 4 -> 1;
      case 5 -> 35;
      default -> 0;
    };
  }

  @Override
  public int getScale(int column) throws SQLException {
    return tag(column) == 5 ? 9 : 0;
  }

  @Override
  public String getTableName(int column) throws SQLException {
    return "";
  }

  @Override
  public String getCatalogName(int column) throws SQLException {
    return "";
  }

  @Override
  public boolean isReadOnly(int column) throws SQLException {
    return true;
  }

  @Override
  public boolean isWritable(int column) throws SQLException {
    return false;
  }

  @Override
  public boolean isDefinitelyWritable(int column) throws SQLException {
    return false;
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) return iface.cast(this);
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass());
  }
}
