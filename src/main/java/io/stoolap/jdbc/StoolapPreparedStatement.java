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

import io.stoolap.StoolapException;
import io.stoolap.StoolapStmt;
import io.stoolap.internal.BulkDecoder;
import io.stoolap.internal.NativeBridge;
import io.stoolap.internal.ParamEncoder;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * JDBC PreparedStatement backed by a native Stoolap prepared statement. Parameters use $1, $2, ...
 * syntax mapped from 1-based JDBC indices.
 */
public class StoolapPreparedStatement extends StoolapStatement implements PreparedStatement {

  private final StoolapStmt nativeStmt;
  private final String sql;
  private Object[] params;
  private int maxParam;
  private final List<Object[]> batch = new ArrayList<>();

  StoolapPreparedStatement(StoolapConnection conn, String sql) throws SQLException {
    super(conn);
    this.sql = sql;
    this.params = new Object[8];
    try {
      this.nativeStmt = conn.getDb().prepare(sql);
    } catch (StoolapException e) {
      throw new SQLException("Failed to prepare: " + e.getMessage(), e);
    }
  }

  private Object[] getParamArray() {
    Object[] result = new Object[maxParam];
    System.arraycopy(params, 0, result, 0, maxParam);
    return result;
  }

  private void ensureCapacity(int index) {
    if (index > params.length) {
      Object[] newParams = new Object[Math.max(index, params.length * 2)];
      System.arraycopy(params, 0, newParams, 0, params.length);
      params = newParams;
    }
    if (index > maxParam) maxParam = index;
  }

  @Override
  public ResultSet executeQuery() throws SQLException {
    checkClosed();
    closeCurrentResultSet();
    try {
      conn.ensureTx();
      long txPtr = conn.getActiveTxPtr();
      byte[] buf;
      byte[] binParams = ParamEncoder.encode(getParamArray());
      if (txPtr != 0) {
        buf = NativeBridge.txStmtQuery(txPtr, nativeStmt.ptr(), binParams);
      } else {
        buf = NativeBridge.stmtQuery(nativeStmt.ptr(), binParams);
      }
      currentResultSet = new StoolapResultSet(this, BulkDecoder.decode(buf));
      return currentResultSet;
    } catch (Exception e) {
      throw new SQLException(e.getMessage(), e);
    }
  }

  @Override
  public int executeUpdate() throws SQLException {
    checkClosed();
    closeCurrentResultSet();
    try {
      conn.ensureTx();
      long txPtr = conn.getActiveTxPtr();
      long affected;
      byte[] binParams = ParamEncoder.encode(getParamArray());
      if (txPtr != 0) {
        affected = NativeBridge.txStmtExec(txPtr, nativeStmt.ptr(), binParams);
      } else {
        affected = NativeBridge.stmtExec(nativeStmt.ptr(), binParams);
      }
      updateCount = affected;
      return (int) affected;
    } catch (Exception e) {
      throw new SQLException(e.getMessage(), e);
    }
  }

  @Override
  public boolean execute() throws SQLException {
    String trimmed = sql.trim().toUpperCase();
    if (trimmed.startsWith("SELECT")
        || trimmed.startsWith("WITH")
        || trimmed.startsWith("EXPLAIN")
        || trimmed.startsWith("SHOW")
        || trimmed.startsWith("VALUES")) {
      executeQuery();
      return true;
    } else {
      executeUpdate();
      return false;
    }
  }

  @Override
  public void addBatch() throws SQLException {
    batch.add(getParamArray());
    clearParameters();
  }

  @Override
  public int[] executeBatch() throws SQLException {
    checkClosed();
    try {
      conn.ensureTx();
      long txPtr = conn.getActiveTxPtr();
      byte[] binBatch = ParamEncoder.encodeBatch(batch.toArray(new Object[0][]));
      long[] results;
      if (txPtr != 0) {
        results = NativeBridge.txStmtExecBatch(txPtr, nativeStmt.ptr(), binBatch);
      } else {
        results = NativeBridge.stmtExecBatch(nativeStmt.ptr(), binBatch);
      }
      int[] intResults = new int[results.length];
      for (int i = 0; i < results.length; i++) intResults[i] = (int) results[i];
      return intResults;
    } catch (Exception e) {
      throw new BatchUpdateException(e.getMessage(), new int[0], e);
    } finally {
      batch.clear();
    }
  }

  @Override
  public void clearBatch() throws SQLException {
    batch.clear();
  }

  @Override
  public void clearParameters() throws SQLException {
    java.util.Arrays.fill(params, 0, maxParam, null);
    maxParam = 0;
  }

  // --- Parameter setters (zero-allocation path for primitives via boxing) ---

  @Override
  public void setNull(int i, int t) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = null;
  }

  @Override
  public void setBoolean(int i, boolean x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = x;
  }

  @Override
  public void setByte(int i, byte x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = (long) x;
  }

  @Override
  public void setShort(int i, short x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = (long) x;
  }

  @Override
  public void setInt(int i, int x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = (long) x;
  }

  @Override
  public void setLong(int i, long x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = x;
  }

  @Override
  public void setFloat(int i, float x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = (double) x;
  }

  @Override
  public void setDouble(int i, double x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = x;
  }

  @Override
  public void setBigDecimal(int i, BigDecimal x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = x != null ? x.doubleValue() : null;
  }

  @Override
  public void setString(int i, String x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = x;
  }

  @Override
  public void setBytes(int i, byte[] x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = x;
  }

  @Override
  public void setDate(int i, Date x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = x != null ? x.toString() : null;
  }

  @Override
  public void setTime(int i, Time x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = x != null ? x.toString() : null;
  }

  @Override
  public void setTimestamp(int i, Timestamp x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = x;
  }

  @Override
  public void setObject(int i, Object x) throws SQLException {
    ensureCapacity(i);
    params[i - 1] = x;
  }

  @Override
  public void setObject(int i, Object x, int t) throws SQLException {
    setObject(i, x);
  }

  @Override
  public void setObject(int i, Object x, int t, int s) throws SQLException {
    setObject(i, x);
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return currentResultSet != null ? currentResultSet.getMetaData() : null;
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void close() throws SQLException {
    if (!closed) {
      closed = true;
      closeCurrentResultSet();
      nativeStmt.close();
    }
  }

  // --- Unsupported setters ---
  @Override
  public void setAsciiStream(int i, InputStream x, int l) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  @SuppressWarnings("deprecation")
  public void setUnicodeStream(int i, InputStream x, int l) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBinaryStream(int i, InputStream x, int l) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setRef(int i, Ref x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBlob(int i, Blob x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClob(int i, Clob x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setArray(int i, Array x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setDate(int i, Date x, Calendar c) throws SQLException {
    setDate(i, x);
  }

  @Override
  public void setTime(int i, Time x, Calendar c) throws SQLException {
    setTime(i, x);
  }

  @Override
  public void setTimestamp(int i, Timestamp x, Calendar c) throws SQLException {
    setTimestamp(i, x);
  }

  @Override
  public void setNull(int i, int t, String n) throws SQLException {
    setNull(i, t);
  }

  @Override
  public void setURL(int i, URL x) throws SQLException {
    setString(i, x != null ? x.toString() : null);
  }

  @Override
  public void setRowId(int i, RowId x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNString(int i, String v) throws SQLException {
    setString(i, v);
  }

  @Override
  public void setNCharacterStream(int i, Reader v, long l) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNClob(int i, NClob v) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClob(int i, Reader r, long l) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBlob(int i, InputStream s, long l) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNClob(int i, Reader r, long l) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setSQLXML(int i, SQLXML x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setAsciiStream(int i, InputStream x, long l) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBinaryStream(int i, InputStream x, long l) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setCharacterStream(int i, Reader r, long l) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setAsciiStream(int i, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBinaryStream(int i, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setCharacterStream(int i, Reader r) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNCharacterStream(int i, Reader v) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClob(int i, Reader r) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBlob(int i, InputStream s) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNClob(int i, Reader r) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setCharacterStream(int i, Reader r, int l) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }
}
