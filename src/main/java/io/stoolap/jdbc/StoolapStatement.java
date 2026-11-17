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
import io.stoolap.StoolapTx;
import io.stoolap.internal.ChunkReader;
import io.stoolap.internal.NativeBridge;
import io.stoolap.internal.ParamEncoder;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDBC Statement. Results stream from the engine in chunks of the fetch size (1024 rows by
 * default). {@link #execute(String)} reports a result set exactly when the statement returns
 * columns, so a query with a leading comment or {@code INSERT ... RETURNING} is a query.
 *
 * <p>A query timeout is enforced by the engine for statements that run outside a transaction
 * (auto-commit, or DDL); inside a manual-commit transaction it is not enforced.
 */
public class StoolapStatement implements Statement {

  /** Rows fetched per JNI call when no fetch size is set. */
  static final int DEFAULT_FETCH_SIZE = 1024;

  /** Asks for generated keys from the target table's primary key. */
  static final Object PRIMARY_KEY = new Object();

  /** Placement code meaning "work it out from the SQL text". */
  static final int PLACE_BY_SQL = -1;

  protected final StoolapConnection conn;
  protected StoolapResultSet currentResultSet;
  protected StoolapResultSet generatedKeys;
  protected long updateCount = -1;
  protected volatile boolean closed;
  private int fetchSize;
  private long maxRows;
  private int queryTimeout;
  private boolean closeOnCompletion;
  private final List<String> sqlBatch = new ArrayList<>();

  StoolapStatement(StoolapConnection conn) {
    this.conn = conn;
    conn.register(this);
  }

  // ---- Running statements ----

  private int chunkSize() {
    int chunk = fetchSize > 0 ? fetchSize : DEFAULT_FETCH_SIZE;
    return maxRows > 0 && maxRows < chunk ? (int) maxRows : chunk;
  }

  private int timeoutMs() {
    return (int) Math.min((long) queryTimeout * 1000, Integer.MAX_VALUE);
  }

  /**
   * Where {@code sql} runs: {@code placement} itself, or worked out from the text. Called with the
   * connection's lock held.
   */
  private int place(String sql, int placement) throws SQLException {
    if (!conn.autoCommitNow()) {
      return placement != PLACE_BY_SQL ? placement : NativeBridge.placement(sql);
    }
    return NativeBridge.COMMIT_FIRST;
  }

  /**
   * Handles BEGIN, COMMIT and ROLLBACK on a manual-commit connection by ending the open
   * transaction; returns whether it did. Called with the connection's lock held.
   */
  private boolean endedTransaction(int placement) throws SQLException {
    if (placement == NativeBridge.COMMIT) {
      conn.commit();
      return true;
    }
    if (placement == NativeBridge.ROLLBACK) {
      conn.rollback();
      return true;
    }
    return false;
  }

  private static final byte[] NO_COLUMNS = new byte[24]; // handle 0, 0 columns, 0 rows, 0 affected

  /** Runs a statement for its result; {@code prepared} is null for SQL text. */
  ChunkReader open(String sql, StoolapStmt prepared, Object[] params, int placement)
      throws SQLException {
    String text = prepared != null ? prepared.getSql() : sql;
    int chunk = chunkSize();
    int timeoutMs = timeoutMs();
    synchronized (conn) {
      int p = place(text, placement);
      if (endedTransaction(p)) return new ChunkReader(NO_COLUMNS, chunk);
      StoolapTx tx = conn.txFor(p);
      byte[] buf;
      if (tx != null) {
        buf =
            prepared != null
                ? tx.handle()
                    .exclusive(
                        t ->
                            prepared
                                .handle()
                                .shared(
                                    s ->
                                        NativeBridge.txStmtQueryCursor(
                                            t, s, ParamEncoder.encode(params), chunk)))
                : tx.handle()
                    .exclusive(
                        t ->
                            NativeBridge.txQueryCursor(
                                t, text, ParamEncoder.encode(params), chunk));
      } else if (prepared != null && timeoutMs == 0) {
        buf =
            prepared
                .handle()
                .shared(s -> NativeBridge.stmtQueryCursor(s, ParamEncoder.encode(params), chunk));
      } else {
        buf =
            conn.getDb()
                .handle()
                .shared(
                    d ->
                        NativeBridge.queryCursor(
                            d, text, ParamEncoder.encode(params), chunk, timeoutMs));
      }
      return new ChunkReader(buf, chunk);
    }
  }

  /** Runs a statement for its update count; {@code prepared} is null for SQL text. */
  long update(String sql, StoolapStmt prepared, Object[] params, int placement)
      throws SQLException {
    String text = prepared != null ? prepared.getSql() : sql;
    int timeoutMs = timeoutMs();
    synchronized (conn) {
      int p = place(text, placement);
      if (endedTransaction(p)) return 0;
      StoolapTx tx = conn.txFor(p);
      if (tx != null) {
        return prepared != null
            ? tx.handle()
                .exclusive(
                    t ->
                        prepared
                            .handle()
                            .shared(
                                s -> NativeBridge.txStmtExec(t, s, ParamEncoder.encode(params))))
            : tx.handle()
                .exclusive(t -> NativeBridge.txExecParams(t, text, ParamEncoder.encode(params)));
      }
      if (prepared != null && timeoutMs == 0) {
        return prepared.handle().shared(s -> NativeBridge.stmtExec(s, ParamEncoder.encode(params)));
      }
      return conn.getDb()
          .handle()
          .shared(d -> NativeBridge.execParams(d, text, ParamEncoder.encode(params), timeoutMs));
    }
  }

  /** Runs a prepared statement once per parameter set, atomically outside a transaction. */
  long[] batch(StoolapStmt prepared, Object[][] rows, int placement) throws SQLException {
    synchronized (conn) {
      StoolapTx tx = conn.txFor(place(prepared.getSql(), placement));
      byte[] bin = ParamEncoder.encodeBatch(rows);
      if (tx != null) {
        return tx.handle()
            .exclusive(t -> prepared.handle().shared(s -> NativeBridge.txStmtExecBatch(t, s, bin)));
      }
      return conn.getDb()
          .handle()
          .shared(d -> prepared.handle().shared(s -> NativeBridge.stmtExecBatch(d, s, bin)));
    }
  }

  /** Starts a run: refuses a closed statement and drops the previous run's results. */
  protected void beginRun() throws SQLException {
    checkClosed();
    conn.checkClosed();
    closeResults();
  }

  /** Makes {@code reader} the current result set, or the update count when it has no columns. */
  protected boolean present(ChunkReader reader) {
    if (reader.columnNames().length == 0) {
      updateCount = reader.rowsAffected();
      reader.close();
      return false;
    }
    currentResultSet = new StoolapResultSet(this, reader, maxRows);
    return true;
  }

  protected ResultSet queryResult(ChunkReader reader) throws SQLException {
    if (!present(reader)) {
      throw new SQLException("The statement did not return a result set", "02000");
    }
    return currentResultSet;
  }

  // ---- Generated keys ----

  private static final Pattern INSERT_INTO =
      Pattern.compile(
          "^\\s*INSERT\\s+INTO\\s+(\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$.]*)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern RETURNING =
      Pattern.compile("\\bRETURNING\\b", Pattern.CASE_INSENSITIVE);

  /**
   * {@code sql} with a RETURNING clause for the generated keys {@code spec} asks for ({@link
   * #PRIMARY_KEY}, column names or 1-based column indexes), or null when there is nothing to add:
   * no spec, not an INSERT, a RETURNING clause already, or no primary key.
   */
  static String withReturning(StoolapConnection conn, String sql, Object spec) throws SQLException {
    if (spec == null) return null;
    Matcher m = INSERT_INTO.matcher(sql);
    if (!m.find() || RETURNING.matcher(sql).find()) return null;
    String table = StoolapDatabaseMetaData.unquote(m.group(1));
    List<String> columns = new ArrayList<>();
    if (spec instanceof String[] names) {
      columns.addAll(Arrays.asList(names));
    } else {
      List<Object[]> described = StoolapDatabaseMetaData.describe(conn, table);
      if (spec instanceof int[] indexes) {
        for (int i : indexes) {
          if (i < 1 || i > described.size()) {
            throw new SQLException("No column " + i + " in " + table, "42703");
          }
          columns.add((String) described.get(i - 1)[0]);
        }
      } else {
        for (Object[] col : described) {
          if ("PRI".equals(col[3])) columns.add((String) col[0]);
        }
      }
    }
    if (columns.isEmpty()) return null;
    StringBuilder out = new StringBuilder(sql.strip());
    while (out.length() > 0 && out.charAt(out.length() - 1) == ';') {
      out.setLength(out.length() - 1);
    }
    out.append(" RETURNING ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) out.append(", ");
      out.append(StoolapDatabaseMetaData.quote(columns.get(i)));
    }
    return out.toString();
  }

  /** Reads a RETURNING result into the generated keys; returns the row count. */
  protected long keepKeys(ChunkReader reader) throws SQLException {
    List<Object[]> rows = new ArrayList<>();
    try {
      for (Object[] row; (row = reader.next()) != null; ) rows.add(row);
    } finally {
      reader.close();
    }
    generatedKeys = new StoolapResultSet(this, reader.columnNames(), rows);
    updateCount = rows.size();
    return rows.size();
  }

  private long updateWithKeys(String sql, Object spec) throws SQLException {
    beginRun();
    String keysSql = withReturning(conn, sql, spec);
    if (keysSql == null) {
      updateCount = update(sql, null, null, PLACE_BY_SQL);
      return updateCount;
    }
    return keepKeys(open(keysSql, null, null, PLACE_BY_SQL));
  }

  private boolean executeWithKeys(String sql, Object spec) throws SQLException {
    if (withReturning(conn, sql, spec) == null) return execute(sql);
    updateWithKeys(sql, spec);
    return false;
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    checkClosed();
    if (generatedKeys == null) {
      generatedKeys = new StoolapResultSet(this, new String[0], List.of());
    }
    return generatedKeys;
  }

  // ---- Statement ----

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    beginRun();
    return queryResult(open(sql, null, null, PLACE_BY_SQL));
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    return (int) Math.min(executeLargeUpdate(sql), Integer.MAX_VALUE);
  }

  @Override
  public long executeLargeUpdate(String sql) throws SQLException {
    beginRun();
    updateCount = update(sql, null, null, PLACE_BY_SQL);
    return updateCount;
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    beginRun();
    return present(open(sql, null, null, PLACE_BY_SQL));
  }

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    return (int) Math.min(executeLargeUpdate(sql, autoGeneratedKeys), Integer.MAX_VALUE);
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    return (int) Math.min(executeLargeUpdate(sql, columnIndexes), Integer.MAX_VALUE);
  }

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {
    return (int) Math.min(executeLargeUpdate(sql, columnNames), Integer.MAX_VALUE);
  }

  @Override
  public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    if (autoGeneratedKeys != RETURN_GENERATED_KEYS) return executeLargeUpdate(sql);
    return updateWithKeys(sql, PRIMARY_KEY);
  }

  @Override
  public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
    return updateWithKeys(sql, columnIndexes);
  }

  @Override
  public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
    return updateWithKeys(sql, columnNames);
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    if (autoGeneratedKeys != RETURN_GENERATED_KEYS) return execute(sql);
    return executeWithKeys(sql, PRIMARY_KEY);
  }

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    return executeWithKeys(sql, columnIndexes);
  }

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    return executeWithKeys(sql, columnNames);
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    checkClosed();
    return currentResultSet;
  }

  @Override
  public int getUpdateCount() throws SQLException {
    checkClosed();
    return (int) Math.min(updateCount, Integer.MAX_VALUE);
  }

  @Override
  public long getLargeUpdateCount() throws SQLException {
    checkClosed();
    return updateCount;
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return getMoreResults(CLOSE_CURRENT_RESULT);
  }

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    checkClosed();
    if (current != KEEP_CURRENT_RESULT && currentResultSet != null) currentResultSet.close();
    currentResultSet = null;
    updateCount = -1;
    return false;
  }

  // ---- Batches of SQL text ----

  @Override
  public void addBatch(String sql) throws SQLException {
    checkClosed();
    sqlBatch.add(sql);
  }

  @Override
  public void clearBatch() throws SQLException {
    checkClosed();
    sqlBatch.clear();
  }

  @Override
  public int[] executeBatch() throws SQLException {
    long[] counts = executeLargeBatch();
    int[] out = new int[counts.length];
    for (int i = 0; i < counts.length; i++) out[i] = (int) Math.min(counts[i], Integer.MAX_VALUE);
    return out;
  }

  /** Runs each statement in turn; a failure reports the counts of those before it. */
  @Override
  public long[] executeLargeBatch() throws SQLException {
    beginRun();
    List<String> sqls = new ArrayList<>(sqlBatch);
    sqlBatch.clear();
    long[] counts = new long[sqls.size()];
    for (int i = 0; i < counts.length; i++) {
      try {
        counts[i] = update(sqls.get(i), null, null, PLACE_BY_SQL);
      } catch (SQLException e) {
        throw new BatchUpdateException(
            "batch row " + i + ": " + e.getMessage(),
            e.getSQLState(),
            0,
            Arrays.copyOf(counts, i),
            e);
      }
    }
    return counts;
  }

  // ---- Settings ----

  @Override
  public int getMaxFieldSize() throws SQLException {
    checkClosed();
    return 0;
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    checkClosed();
    if (max < 0) throw new SQLException("max field size must not be negative", "22023");
  }

  @Override
  public int getMaxRows() throws SQLException {
    checkClosed();
    return (int) Math.min(maxRows, Integer.MAX_VALUE);
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    setLargeMaxRows(max);
  }

  @Override
  public long getLargeMaxRows() throws SQLException {
    checkClosed();
    return maxRows;
  }

  @Override
  public void setLargeMaxRows(long max) throws SQLException {
    checkClosed();
    if (max < 0) throw new SQLException("max rows must not be negative", "22023");
    maxRows = max;
  }

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    checkClosed();
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    checkClosed();
    return queryTimeout;
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    checkClosed();
    if (seconds < 0) throw new SQLException("query timeout must not be negative", "22023");
    queryTimeout = seconds;
  }

  /** Not supported: the engine cannot interrupt a running statement; use a query timeout. */
  @Override
  public void cancel() throws SQLException {
    throw new SQLFeatureNotSupportedException(
        "Stoolap cannot cancel a running statement; use setQueryTimeout", "0A000");
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
  public void setCursorName(String name) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    checkClosed();
    if (direction != ResultSet.FETCH_FORWARD) {
      throw new SQLFeatureNotSupportedException("Only FETCH_FORWARD is supported");
    }
  }

  @Override
  public int getFetchDirection() throws SQLException {
    checkClosed();
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    checkClosed();
    if (rows < 0) throw new SQLException("fetch size must not be negative", "22023");
    fetchSize = rows;
  }

  @Override
  public int getFetchSize() throws SQLException {
    checkClosed();
    return fetchSize;
  }

  @Override
  public int getResultSetConcurrency() throws SQLException {
    checkClosed();
    return ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public int getResultSetType() throws SQLException {
    checkClosed();
    return ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    checkClosed();
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public Connection getConnection() throws SQLException {
    checkClosed();
    return conn;
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    checkClosed();
  }

  @Override
  public boolean isPoolable() throws SQLException {
    checkClosed();
    return false;
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    checkClosed();
    closeOnCompletion = true;
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    checkClosed();
    return closeOnCompletion;
  }

  // ---- Lifecycle ----

  /** Called by a result set of this statement when it closes. */
  void resultSetClosed(StoolapResultSet rs) {
    if (closeOnCompletion && rs == currentResultSet && !closed) {
      try {
        close();
      } catch (SQLException e) {
        // closing a statement whose result set already closed does not fail
      }
    }
  }

  protected void closeResults() throws SQLException {
    StoolapResultSet rs = currentResultSet;
    currentResultSet = null;
    if (rs != null) rs.close();
    StoolapResultSet keys = generatedKeys;
    generatedKeys = null;
    if (keys != null) keys.close();
    updateCount = -1;
  }

  @Override
  public void close() throws SQLException {
    if (closed) return;
    closed = true;
    try {
      closeResults();
    } finally {
      conn.unregister(this);
    }
  }

  @Override
  public boolean isClosed() throws SQLException {
    return closed;
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
    if (closed) throw new SQLException("Statement is closed", "HY010");
  }
}
