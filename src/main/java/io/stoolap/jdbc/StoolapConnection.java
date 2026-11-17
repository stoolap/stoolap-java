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
import io.stoolap.internal.NativeBridge;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * JDBC Connection over one {@link StoolapDB} handle.
 *
 * <p>Statements on a connection run one at a time; a connection may be shared between threads but
 * is meant for one thread at a time, as pools hand it out.
 *
 * <p>With auto-commit off, DML runs in a transaction that begins with the first statement. A
 * Stoolap transaction accepts only DML and savepoints, so DDL commits the open transaction and runs
 * on its own, as in MySQL and Oracle. SHOW, DESCRIBE and EXPLAIN run outside the transaction and
 * leave it open. The SQL statements BEGIN and COMMIT commit the open transaction, and ROLLBACK
 * rolls it back.
 */
public class StoolapConnection implements Connection {

  private final StoolapDB db;
  private final Set<StoolapStatement> statements =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private StoolapTx activeTx;
  private boolean autoCommit = true;
  private int isolation = TRANSACTION_READ_COMMITTED;
  private int savepoints;
  private volatile boolean closed;

  StoolapConnection(StoolapDB db) {
    this.db = db;
  }

  StoolapDB getDb() {
    return db;
  }

  synchronized void register(StoolapStatement s) {
    statements.add(s);
  }

  synchronized void unregister(StoolapStatement s) {
    statements.remove(s);
  }

  /** The auto-commit mode; the caller holds the connection's lock. */
  boolean autoCommitNow() {
    return autoCommit;
  }

  /**
   * The transaction a statement placed at {@code placement} runs in, or null to run it on the
   * database, after committing the open transaction when the placement asks for it. The caller
   * holds the connection's lock.
   */
  StoolapTx txFor(int placement) throws SQLException {
    checkClosed();
    if (autoCommit) return null;
    switch (placement) {
      case NativeBridge.IN_TX:
        if (activeTx == null) activeTx = db.begin(isolation == TRANSACTION_REPEATABLE_READ);
        return activeTx;
      case NativeBridge.OUTSIDE:
        return null;
      default:
        commitActive();
        return null;
    }
  }

  private void commitActive() throws SQLException {
    if (activeTx != null) {
      activeTx.commit(); // a failed commit leaves the transaction for rollback()
      activeTx = null;
    }
  }

  private void rollbackActive() throws SQLException {
    if (activeTx != null) {
      try {
        activeTx.rollback();
      } finally {
        activeTx = null;
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
    return new StoolapPreparedStatement(this, sql, null);
  }

  @Override
  public CallableStatement prepareCall(String sql) throws SQLException {
    throw new SQLFeatureNotSupportedException("CallableStatement not supported");
  }

  @Override
  public String nativeSQL(String sql) throws SQLException {
    checkClosed();
    return sql;
  }

  @Override
  public synchronized void setAutoCommit(boolean autoCommit) throws SQLException {
    checkClosed();
    if (this.autoCommit == autoCommit) return;
    if (autoCommit) {
      try {
        commitActive();
      } catch (StoolapException e) {
        throw new SQLException(
            "Failed to commit on autocommit change: " + e.getMessage(), e.getSQLState(), e);
      }
    }
    this.autoCommit = autoCommit;
  }

  @Override
  public synchronized boolean getAutoCommit() throws SQLException {
    checkClosed();
    return autoCommit;
  }

  @Override
  public synchronized void commit() throws SQLException {
    checkClosed();
    if (autoCommit) throw new SQLException("Cannot commit in auto-commit mode", "25000");
    commitActive();
  }

  @Override
  public synchronized void rollback() throws SQLException {
    checkClosed();
    if (autoCommit) throw new SQLException("Cannot rollback in auto-commit mode", "25000");
    rollbackActive();
  }

  /** Closes the connection's statements and result sets, rolls back, and closes the handle. */
  @Override
  public void close() throws SQLException {
    List<StoolapStatement> open;
    synchronized (this) {
      if (closed) return;
      closed = true;
      open = new ArrayList<>(statements);
    }
    for (StoolapStatement s : open) {
      try {
        s.close();
      } catch (SQLException e) {
        // best effort; the handle closes below either way
      }
    }
    synchronized (this) {
      try {
        rollbackActive();
      } catch (SQLException e) {
        // best effort rollback on close
      } finally {
        db.close();
      }
    }
  }

  @Override
  public boolean isClosed() throws SQLException {
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
    // Stoolap has no read-only mode; the hint is accepted and ignored, as JDBC allows
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

  /**
   * READ COMMITTED (the default) or REPEATABLE READ, which runs transactions under snapshot
   * isolation; READ UNCOMMITTED is raised to READ COMMITTED. SERIALIZABLE is refused. The level
   * applies from the next transaction.
   */
  @Override
  public synchronized void setTransactionIsolation(int level) throws SQLException {
    checkClosed();
    switch (level) {
      case TRANSACTION_READ_UNCOMMITTED, TRANSACTION_READ_COMMITTED ->
          isolation = TRANSACTION_READ_COMMITTED;
      case TRANSACTION_REPEATABLE_READ -> isolation = TRANSACTION_REPEATABLE_READ;
      default ->
          throw new SQLFeatureNotSupportedException(
              "Stoolap supports READ COMMITTED and REPEATABLE READ (snapshot) isolation", "0A000");
    }
  }

  @Override
  public synchronized int getTransactionIsolation() throws SQLException {
    checkClosed();
    return isolation;
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    checkClosed();
    return null;
  }

  @Override
  public void clearWarnings() throws SQLException {
    checkClosed();
  }

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
  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    throw new SQLFeatureNotSupportedException("CallableStatement not supported");
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
  public void setHoldability(int holdability) throws SQLException {
    checkClosed();
  }

  @Override
  public int getHoldability() throws SQLException {
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  // ---- Savepoints ----

  private record StoolapSavepoint(int id, String name) implements Savepoint {
    @Override
    public int getSavepointId() throws SQLException {
      if (name != null) throw new SQLException("This savepoint is named", "3B000");
      return id;
    }

    @Override
    public String getSavepointName() throws SQLException {
      if (name == null) throw new SQLException("This savepoint is unnamed", "3B000");
      return name;
    }

    String sqlName() {
      return name != null ? name : "jdbc_savepoint_" + id;
    }
  }

  private synchronized void runInTx(String sql) throws SQLException {
    if (autoCommit) throw new SQLException("Savepoints need auto-commit off", "3B000");
    txFor(NativeBridge.IN_TX).execute(sql);
  }

  private static StoolapSavepoint ours(Savepoint s) throws SQLException {
    if (s instanceof StoolapSavepoint sp) return sp;
    throw new SQLException("Not a savepoint of this connection", "3B000");
  }

  @Override
  public synchronized Savepoint setSavepoint() throws SQLException {
    checkClosed();
    StoolapSavepoint sp = new StoolapSavepoint(++savepoints, null);
    runInTx("SAVEPOINT " + sp.sqlName());
    return sp;
  }

  @Override
  public synchronized Savepoint setSavepoint(String name) throws SQLException {
    checkClosed();
    if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new SQLException("A savepoint name must be an identifier: " + name, "3B000");
    }
    StoolapSavepoint sp = new StoolapSavepoint(++savepoints, name);
    runInTx("SAVEPOINT " + sp.sqlName());
    return sp;
  }

  @Override
  public synchronized void rollback(Savepoint savepoint) throws SQLException {
    checkClosed();
    String name = ours(savepoint).sqlName();
    if (activeTx == null) throw new SQLException("No transaction holds " + name, "3B001");
    runInTx("ROLLBACK TO SAVEPOINT " + name);
  }

  @Override
  public synchronized void releaseSavepoint(Savepoint savepoint) throws SQLException {
    checkClosed();
    String name = ours(savepoint).sqlName();
    if (activeTx == null) throw new SQLException("No transaction holds " + name, "3B001");
    runInTx("RELEASE SAVEPOINT " + name);
  }

  // ---- Statement variants ----

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
  public CallableStatement prepareCall(
      String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    throw new SQLFeatureNotSupportedException("CallableStatement not supported");
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
    checkClosed();
    return new StoolapPreparedStatement(
        this,
        sql,
        autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS ? StoolapStatement.PRIMARY_KEY : null);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
    checkClosed();
    return new StoolapPreparedStatement(this, sql, columnIndexes);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
    checkClosed();
    return new StoolapPreparedStatement(this, sql, columnNames);
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
  public boolean isValid(int timeout) throws SQLException {
    if (timeout < 0) throw new SQLException("timeout must not be negative", "22023");
    return !closed && !db.isClosed();
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
  public void setSchema(String schema) throws SQLException {
    checkClosed();
  }

  @Override
  public String getSchema() throws SQLException {
    checkClosed();
    return null;
  }

  @Override
  public void abort(Executor executor) throws SQLException {
    close();
  }

  @Override
  public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
    checkClosed();
  }

  @Override
  public int getNetworkTimeout() throws SQLException {
    return 0;
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

  void checkClosed() throws SQLException {
    if (closed) throw new SQLException("Connection is closed", "08003");
  }
}
