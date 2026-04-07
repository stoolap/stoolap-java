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

import io.stoolap.internal.BulkDecoder;
import io.stoolap.internal.NativeBridge;
import java.sql.*;

/** JDBC Statement backed by JNI calls to the Rust engine. */
public class StoolapStatement implements Statement {

  protected final StoolapConnection conn;
  protected StoolapResultSet currentResultSet;
  protected long updateCount = -1;
  protected boolean closed;

  StoolapStatement(StoolapConnection conn) {
    this.conn = conn;
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    checkClosed();
    closeCurrentResultSet();
    try {
      conn.ensureTx();
      byte[] buf;
      long txPtr = conn.getActiveTxPtr();
      if (txPtr != 0) {
        buf = NativeBridge.txQuery(txPtr, sql);
      } else {
        buf = NativeBridge.query(conn.getDbPtr(), sql);
      }
      currentResultSet = new StoolapResultSet(this, BulkDecoder.decode(buf));
      return currentResultSet;
    } catch (Exception e) {
      throw new SQLException(e.getMessage(), e);
    }
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    checkClosed();
    closeCurrentResultSet();
    try {
      conn.ensureTx();
      long affected;
      long txPtr = conn.getActiveTxPtr();
      if (txPtr != 0) {
        affected = NativeBridge.txExec(txPtr, sql);
      } else {
        affected = NativeBridge.exec(conn.getDbPtr(), sql);
      }
      updateCount = affected;
      return (int) affected;
    } catch (Exception e) {
      throw new SQLException(e.getMessage(), e);
    }
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    checkClosed();
    closeCurrentResultSet();
    String trimmed = sql.trim().toUpperCase();
    if (trimmed.startsWith("SELECT")
        || trimmed.startsWith("WITH")
        || trimmed.startsWith("EXPLAIN")
        || trimmed.startsWith("SHOW")
        || trimmed.startsWith("PRAGMA")
        || trimmed.startsWith("VALUES")) {
      executeQuery(sql);
      return true;
    } else {
      executeUpdate(sql);
      return false;
    }
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    return currentResultSet;
  }

  @Override
  public int getUpdateCount() throws SQLException {
    return (int) updateCount;
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    closeCurrentResultSet();
    return false;
  }

  @Override
  public void close() throws SQLException {
    if (!closed) {
      closed = true;
      closeCurrentResultSet();
    }
  }

  @Override
  public boolean isClosed() throws SQLException {
    return closed;
  }

  @Override
  public Connection getConnection() throws SQLException {
    return conn;
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    return 0;
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {}

  @Override
  public int getMaxRows() throws SQLException {
    return 0;
  }

  @Override
  public void setMaxRows(int max) throws SQLException {}

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {}

  @Override
  public int getQueryTimeout() throws SQLException {
    return 0;
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {}

  @Override
  public void cancel() throws SQLException {}

  @Override
  public SQLWarning getWarnings() throws SQLException {
    return null;
  }

  @Override
  public void clearWarnings() throws SQLException {}

  @Override
  public void setCursorName(String name) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {}

  @Override
  public int getFetchDirection() throws SQLException {
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {}

  @Override
  public int getFetchSize() throws SQLException {
    return 0;
  }

  @Override
  public int getResultSetConcurrency() throws SQLException {
    return ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public int getResultSetType() throws SQLException {
    return ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    throw new SQLFeatureNotSupportedException("Use PreparedStatement");
  }

  @Override
  public void clearBatch() throws SQLException {}

  @Override
  public int[] executeBatch() throws SQLException {
    throw new SQLFeatureNotSupportedException("Use PreparedStatement");
  }

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    return false;
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public int executeUpdate(String sql, int a) throws SQLException {
    return executeUpdate(sql);
  }

  @Override
  public int executeUpdate(String sql, int[] a) throws SQLException {
    return executeUpdate(sql);
  }

  @Override
  public int executeUpdate(String sql, String[] a) throws SQLException {
    return executeUpdate(sql);
  }

  @Override
  public boolean execute(String sql, int a) throws SQLException {
    return execute(sql);
  }

  @Override
  public boolean execute(String sql, int[] a) throws SQLException {
    return execute(sql);
  }

  @Override
  public boolean execute(String sql, String[] a) throws SQLException {
    return execute(sql);
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {}

  @Override
  public boolean isPoolable() throws SQLException {
    return false;
  }

  @Override
  public void closeOnCompletion() throws SQLException {}

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
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

  protected void checkClosed() throws SQLException {
    if (closed) throw new SQLException("Statement is closed");
  }

  protected void closeCurrentResultSet() throws SQLException {
    if (currentResultSet != null) {
      currentResultSet.close();
      currentResultSet = null;
    }
    updateCount = -1;
  }
}
