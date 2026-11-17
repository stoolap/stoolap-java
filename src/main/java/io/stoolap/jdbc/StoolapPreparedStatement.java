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

import io.stoolap.StoolapStmt;
import io.stoolap.internal.ChunkReader;
import io.stoolap.internal.NativeBridge;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.List;

/**
 * JDBC PreparedStatement on a statement prepared once, which runs on its compiled plan. Every
 * parameter up to the highest one set must be set before running; values stay set across runs and
 * {@link #addBatch()} until {@link #clearParameters()}.
 */
public class StoolapPreparedStatement extends StoolapStatement implements PreparedStatement {

  private final StoolapStmt nativeStmt;
  private final boolean returnsKeys;
  private final int placement;
  private Object[] params = new Object[8];
  private final BitSet set = new BitSet();
  private int maxParam;
  private final List<Object[]> batch = new ArrayList<>();

  /**
   * @param keys the generated keys to return: null for none, {@link #PRIMARY_KEY}, column names or
   *     1-based column indexes
   */
  StoolapPreparedStatement(StoolapConnection conn, String sql, Object keys) throws SQLException {
    super(conn);
    try {
      String keysSql = withReturning(conn, sql, keys);
      String runSql = keysSql != null ? keysSql : sql;
      this.returnsKeys = keysSql != null;
      this.placement = NativeBridge.placement(runSql);
      this.nativeStmt = conn.getDb().prepare(runSql);
    } catch (SQLException e) {
      conn.unregister(this);
      throw e;
    }
  }

  /** The parameter values, refusing a gap below the highest one set. */
  private Object[] values() throws SQLException {
    int unset = set.nextClearBit(0);
    if (unset < maxParam) {
      throw new SQLException("Parameter " + (unset + 1) + " is not set", "07001");
    }
    return Arrays.copyOf(params, maxParam);
  }

  private void put(int index, Object value) throws SQLException {
    checkClosed();
    if (index < 1) throw new SQLException("Parameter index out of range: " + index, "07009");
    if (index > params.length) params = Arrays.copyOf(params, Math.max(index, params.length * 2));
    params[index - 1] = value;
    set.set(index - 1);
    if (index > maxParam) maxParam = index;
  }

  private void refuseSqlText() throws SQLException {
    throw new SQLException(
        "A PreparedStatement runs its own SQL; use the no-argument form", "HY000");
  }

  // ---- Running ----

  @Override
  public ResultSet executeQuery() throws SQLException {
    beginRun();
    return queryResult(open(null, nativeStmt, values(), placement));
  }

  @Override
  public int executeUpdate() throws SQLException {
    return (int) Math.min(executeLargeUpdate(), Integer.MAX_VALUE);
  }

  @Override
  public long executeLargeUpdate() throws SQLException {
    beginRun();
    if (returnsKeys) return keepKeys(open(null, nativeStmt, values(), placement));
    updateCount = update(null, nativeStmt, values(), placement);
    return updateCount;
  }

  @Override
  public boolean execute() throws SQLException {
    beginRun();
    ChunkReader reader = open(null, nativeStmt, values(), placement);
    if (returnsKeys) {
      keepKeys(reader);
      return false;
    }
    return present(reader);
  }

  @Override
  public void addBatch() throws SQLException {
    checkClosed();
    batch.add(values());
  }

  @Override
  public void clearBatch() throws SQLException {
    checkClosed();
    batch.clear();
  }

  @Override
  public int[] executeBatch() throws SQLException {
    long[] counts = executeLargeBatch();
    int[] out = new int[counts.length];
    for (int i = 0; i < counts.length; i++) out[i] = (int) Math.min(counts[i], Integer.MAX_VALUE);
    return out;
  }

  /**
   * Runs every parameter set added with {@link #addBatch()}. In auto-commit mode the batch runs in
   * a transaction of its own: every row commits, or none does.
   */
  @Override
  public long[] executeLargeBatch() throws SQLException {
    beginRun();
    Object[][] rows = batch.toArray(new Object[0][]);
    batch.clear();
    try {
      if (!returnsKeys) return batch(nativeStmt, rows, placement);
      long[] counts = new long[rows.length];
      List<Object[]> keys = new ArrayList<>();
      String[] keyColumns = new String[0];
      for (int i = 0; i < rows.length; i++) {
        ChunkReader reader = open(null, nativeStmt, rows[i], placement);
        keyColumns = reader.columnNames();
        try {
          for (Object[] row; (row = reader.next()) != null; ) {
            keys.add(row);
            counts[i]++;
          }
        } finally {
          reader.close();
        }
      }
      generatedKeys = new StoolapResultSet(this, keyColumns, keys);
      return counts;
    } catch (BatchUpdateException e) {
      throw e;
    } catch (SQLException e) {
      throw new BatchUpdateException(e.getMessage(), e.getSQLState(), 0, new long[0], e);
    }
  }

  @Override
  public void clearParameters() throws SQLException {
    checkClosed();
    Arrays.fill(params, 0, maxParam, null);
    set.clear();
    maxParam = 0;
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    checkClosed();
    return currentResultSet != null ? currentResultSet.getMetaData() : null;
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void close() throws SQLException {
    if (closed) return;
    try {
      super.close();
    } finally {
      nativeStmt.close();
    }
  }

  // ---- SQL text forms are refused on a PreparedStatement ----

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    refuseSqlText();
    return null;
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    refuseSqlText();
    return 0;
  }

  @Override
  public long executeLargeUpdate(String sql) throws SQLException {
    refuseSqlText();
    return 0;
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    refuseSqlText();
    return false;
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    refuseSqlText();
  }

  // ---- Parameter setters ----

  @Override
  public void setNull(int i, int sqlType) throws SQLException {
    put(i, null);
  }

  @Override
  public void setNull(int i, int sqlType, String typeName) throws SQLException {
    put(i, null);
  }

  @Override
  public void setBoolean(int i, boolean x) throws SQLException {
    put(i, x);
  }

  @Override
  public void setByte(int i, byte x) throws SQLException {
    put(i, (long) x);
  }

  @Override
  public void setShort(int i, short x) throws SQLException {
    put(i, (long) x);
  }

  @Override
  public void setInt(int i, int x) throws SQLException {
    put(i, (long) x);
  }

  @Override
  public void setLong(int i, long x) throws SQLException {
    put(i, x);
  }

  @Override
  public void setFloat(int i, float x) throws SQLException {
    put(i, (double) x);
  }

  @Override
  public void setDouble(int i, double x) throws SQLException {
    put(i, x);
  }

  /** Binds as FLOAT: Stoolap has no DECIMAL type. */
  @Override
  public void setBigDecimal(int i, BigDecimal x) throws SQLException {
    put(i, x != null ? x.doubleValue() : null);
  }

  @Override
  public void setString(int i, String x) throws SQLException {
    put(i, x);
  }

  @Override
  public void setNString(int i, String x) throws SQLException {
    put(i, x);
  }

  @Override
  public void setBytes(int i, byte[] x) throws SQLException {
    throw new SQLFeatureNotSupportedException("Stoolap has no BLOB type");
  }

  /** Binds {@code yyyy-MM-dd} text, which a TIMESTAMP column reads as midnight UTC. */
  @Override
  public void setDate(int i, Date x) throws SQLException {
    put(i, x != null ? x.toString() : null);
  }

  @Override
  public void setDate(int i, Date x, Calendar cal) throws SQLException {
    setDate(i, x);
  }

  /** Binds {@code HH:mm:ss} text, which a TIMESTAMP column reads as that time UTC. */
  @Override
  public void setTime(int i, Time x) throws SQLException {
    put(i, x != null ? x.toString() : null);
  }

  @Override
  public void setTime(int i, Time x, Calendar cal) throws SQLException {
    setTime(i, x);
  }

  /** Binds the timestamp's instant. */
  @Override
  public void setTimestamp(int i, Timestamp x) throws SQLException {
    put(i, x);
  }

  @Override
  public void setTimestamp(int i, Timestamp x, Calendar cal) throws SQLException {
    put(i, x);
  }

  @Override
  public void setObject(int i, Object x) throws SQLException {
    if (x instanceof byte[]) setBytes(i, (byte[]) x);
    put(i, x);
  }

  @Override
  public void setObject(int i, Object x, int targetSqlType) throws SQLException {
    setObject(i, x);
  }

  @Override
  public void setObject(int i, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
    setObject(i, x);
  }

  @Override
  public void setURL(int i, URL x) throws SQLException {
    put(i, x != null ? x.toString() : null);
  }

  private static String read(Reader r, long length) throws SQLException {
    if (r == null) return null;
    StringBuilder sb = new StringBuilder();
    char[] buf = new char[8192];
    try {
      long left = length < 0 ? Long.MAX_VALUE : length;
      for (int n; left > 0 && (n = r.read(buf, 0, (int) Math.min(buf.length, left))) > 0; ) {
        sb.append(buf, 0, n);
        left -= n;
      }
    } catch (IOException e) {
      throw new SQLException("Failed to read the parameter: " + e.getMessage(), "HY000", e);
    }
    return sb.toString();
  }

  @Override
  public void setCharacterStream(int i, Reader r) throws SQLException {
    put(i, read(r, -1));
  }

  @Override
  public void setCharacterStream(int i, Reader r, int length) throws SQLException {
    put(i, read(r, length));
  }

  @Override
  public void setCharacterStream(int i, Reader r, long length) throws SQLException {
    put(i, read(r, length));
  }

  @Override
  public void setNCharacterStream(int i, Reader r) throws SQLException {
    put(i, read(r, -1));
  }

  @Override
  public void setNCharacterStream(int i, Reader r, long length) throws SQLException {
    put(i, read(r, length));
  }

  @Override
  public void setClob(int i, Reader r) throws SQLException {
    put(i, read(r, -1));
  }

  @Override
  public void setClob(int i, Reader r, long length) throws SQLException {
    put(i, read(r, length));
  }

  @Override
  public void setNClob(int i, Reader r) throws SQLException {
    put(i, read(r, -1));
  }

  @Override
  public void setNClob(int i, Reader r, long length) throws SQLException {
    put(i, read(r, length));
  }

  // ---- Unsupported: binary streams, LOB objects, arrays, refs ----

  @Override
  public void setAsciiStream(int i, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setAsciiStream(int i, InputStream x, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setAsciiStream(int i, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  @SuppressWarnings("deprecation")
  public void setUnicodeStream(int i, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBinaryStream(int i, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException("Stoolap has no BLOB type");
  }

  @Override
  public void setBinaryStream(int i, InputStream x, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException("Stoolap has no BLOB type");
  }

  @Override
  public void setBinaryStream(int i, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException("Stoolap has no BLOB type");
  }

  @Override
  public void setBlob(int i, Blob x) throws SQLException {
    throw new SQLFeatureNotSupportedException("Stoolap has no BLOB type");
  }

  @Override
  public void setBlob(int i, InputStream x, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException("Stoolap has no BLOB type");
  }

  @Override
  public void setBlob(int i, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException("Stoolap has no BLOB type");
  }

  @Override
  public void setClob(int i, Clob x) throws SQLException {
    put(i, x != null ? x.getSubString(1, (int) x.length()) : null);
  }

  @Override
  public void setNClob(int i, NClob x) throws SQLException {
    put(i, x != null ? x.getSubString(1, (int) x.length()) : null);
  }

  @Override
  public void setRef(int i, Ref x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setArray(int i, Array x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setRowId(int i, RowId x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setSQLXML(int i, SQLXML x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }
}
