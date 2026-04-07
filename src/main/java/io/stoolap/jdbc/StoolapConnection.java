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

import io.stoolap.StoolapDB;
import io.stoolap.StoolapException;
import io.stoolap.StoolapTx;
import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * JDBC Connection wrapping a StoolapDB handle.
 *
 * <p>Each Connection holds a cloned StoolapDB handle so connections can be pooled and used from
 * different threads (one thread per connection).
 */
public class StoolapConnection implements Connection {

  private StoolapDB db;
  private StoolapTx activeTx;
  private boolean autoCommit = true;
  private boolean closed;

  StoolapConnection(StoolapDB db) {
    this.db = db;
  }

  StoolapDB getDb() {
    return db;
  }

  long getDbPtr() {
    return db.ptr();
  }

  StoolapTx getActiveTx() {
    return activeTx;
  }

  long getActiveTxPtr() {
    return activeTx != null ? activeTx.ptr() : 0;
  }

  void ensureTx() throws SQLException {
    if (!autoCommit && activeTx == null) {
      try {
        activeTx = db.begin();
      } catch (StoolapException e) {
        throw new SQLException("Failed to begin transaction: " + e.getMessage(), e);
      }
    }
  }

  @Override
  public Statement createStatement() throws SQLException {
    checkClosed();
    return new StoolapStatement(this);
  }

  @Override
  public PreparedStatement prepareStatement(String sql) throws SQLException {
    checkClosed();
    return new StoolapPreparedStatement(this, sql);
  }

  @Override
  public void setAutoCommit(boolean autoCommit) throws SQLException {
    checkClosed();
    if (this.autoCommit == autoCommit) return;
    if (!this.autoCommit && activeTx != null) {
      // Switching from manual to auto: commit current tx
      try {
        activeTx.commit();
      } catch (StoolapException e) {
        throw new SQLException("Failed to commit on autocommit change: " + e.getMessage(), e);
      } finally {
        activeTx = null;
      }
    }
    this.autoCommit = autoCommit;
  }

  @Override
  public boolean getAutoCommit() throws SQLException {
    checkClosed();
    return autoCommit;
  }

  @Override
  public void commit() throws SQLException {
    checkClosed();
    if (autoCommit) {
      throw new SQLException("Cannot commit in auto-commit mode");
    }
    if (activeTx != null) {
      try {
        activeTx.commit();
      } catch (StoolapException e) {
        throw new SQLException("Commit failed: " + e.getMessage(), e);
      } finally {
        activeTx = null;
      }
    }
  }

  @Override
  public void rollback() throws SQLException {
    checkClosed();
    if (autoCommit) {
      throw new SQLException("Cannot rollback in auto-commit mode");
    }
    if (activeTx != null) {
      try {
        activeTx.rollback();
      } catch (StoolapException e) {
        throw new SQLException("Rollback failed: " + e.getMessage(), e);
      } finally {
        activeTx = null;
      }
    }
  }

  @Override
  public void close() throws SQLException {
    if (!closed) {
      closed = true;
      if (activeTx != null) {
        try {
          activeTx.rollback();
        } catch (StoolapException e) {
          // Best effort rollback on close
        }
        activeTx = null;
      }
      db.close();
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public DatabaseMetaData getMetaData() throws SQLException {
    checkClosed();
    return new StoolapDatabaseMetaData(this);
  }

  @Override
  public void setReadOnly(boolean readOnly) throws SQLException {
    checkClosed();
    // Stoolap doesn't have a read-only mode, accept silently
  }

  @Override
  public boolean isReadOnly() throws SQLException {
    checkClosed();
    return false;
  }

  @Override
  public void setCatalog(String catalog) throws SQLException {
    checkClosed();
  }

  @Override
  public String getCatalog() throws SQLException {
    checkClosed();
    return null;
  }

  @Override
  public void setTransactionIsolation(int level) throws SQLException {
    checkClosed();
    // Stoolap supports READ_COMMITTED and SNAPSHOT via begin()
  }

  @Override
  public int getTransactionIsolation() throws SQLException {
    checkClosed();
    return Connection.TRANSACTION_READ_COMMITTED;
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    return null;
  }

  @Override
  public void clearWarnings() throws SQLException {}

  @Override
  public Statement createStatement(int resultSetType, int resultSetConcurrency)
      throws SQLException {
    return createStatement();
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    return prepareStatement(sql);
  }

  @Override
  public Statement createStatement(
      int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    return createStatement();
  }

  @Override
  public PreparedStatement prepareStatement(
      String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    return prepareStatement(sql);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
    return prepareStatement(sql);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
    return prepareStatement(sql);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
    return prepareStatement(sql);
  }

  @Override
  public boolean isValid(int timeout) throws SQLException {
    return !closed;
  }

  // --- Unsupported operations ---

  @Override
  public CallableStatement prepareCall(String sql) throws SQLException {
    throw new SQLFeatureNotSupportedException("CallableStatement not supported");
  }

  @Override
  public String nativeSQL(String sql) throws SQLException {
    return sql;
  }

  @Override
  public Map<String, Class<?>> getTypeMap() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setHoldability(int holdability) throws SQLException {}

  @Override
  public int getHoldability() throws SQLException {
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public Savepoint setSavepoint() throws SQLException {
    throw new SQLFeatureNotSupportedException("Savepoints not supported");
  }

  @Override
  public Savepoint setSavepoint(String name) throws SQLException {
    throw new SQLFeatureNotSupportedException("Savepoints not supported");
  }

  @Override
  public void rollback(Savepoint savepoint) throws SQLException {
    throw new SQLFeatureNotSupportedException("Savepoints not supported");
  }

  @Override
  public void releaseSavepoint(Savepoint savepoint) throws SQLException {
    throw new SQLFeatureNotSupportedException("Savepoints not supported");
  }

  @Override
  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    throw new SQLFeatureNotSupportedException("CallableStatement not supported");
  }

  @Override
  public CallableStatement prepareCall(
      String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    throw new SQLFeatureNotSupportedException("CallableStatement not supported");
  }

  @Override
  public Clob createClob() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public Blob createBlob() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public NClob createNClob() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public SQLXML createSQLXML() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClientInfo(String name, String value) throws SQLClientInfoException {}

  @Override
  public void setClientInfo(Properties properties) throws SQLClientInfoException {}

  @Override
  public String getClientInfo(String name) throws SQLException {
    return null;
  }

  @Override
  public Properties getClientInfo() throws SQLException {
    return new Properties();
  }

  @Override
  public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setSchema(String schema) throws SQLException {}

  @Override
  public String getSchema() throws SQLException {
    return null;
  }

  @Override
  public void abort(Executor executor) throws SQLException {
    close();
  }

  @Override
  public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {}

  @Override
  public int getNetworkTimeout() throws SQLException {
    return 0;
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass());
  }

  private void checkClosed() throws SQLException {
    if (closed) {
      throw new SQLException("Connection is closed");
    }
  }
}
