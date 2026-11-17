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
package io.stoolap;

import io.stoolap.internal.BulkDecoder;
import io.stoolap.internal.ChunkReader;
import io.stoolap.internal.NativeBridge;
import io.stoolap.internal.NativeHandle;
import io.stoolap.internal.ParamEncoder;
import java.util.Map;

/**
 * A connection to a Stoolap database.
 *
 * <p>Each handle maps to a Rust {@code Box<Database>}. A handle is thread-safe: several threads may
 * run statements on it at once, and {@link #close()} waits for the statements in flight. {@link
 * #cloneHandle()} gives another handle on the same engine.
 *
 * <pre>{@code
 * try (StoolapDB db = StoolapDB.openInMemory()) {
 *     db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
 *     db.execute("INSERT INTO t VALUES (1, 'Alice')");
 *     BulkDecoder.Result result = db.query("SELECT * FROM t");
 *     // ...
 * }
 * }</pre>
 */
public class StoolapDB implements AutoCloseable {

  /** Rows a cursor fetches per JNI call. */
  static final int CHUNK_SIZE = 1024;

  private final NativeHandle handle;

  StoolapDB(long ptr) {
    this.handle = new NativeHandle(this, ptr, NativeBridge::closeDb, "Database");
  }

  public static StoolapDB open(String dsn) throws StoolapException {
    try {
      return new StoolapDB(NativeBridge.open(dsn));
    } catch (Exception e) {
      throw new StoolapException("Failed to open: " + e.getMessage(), e);
    }
  }

  public static StoolapDB openInMemory() throws StoolapException {
    try {
      return new StoolapDB(NativeBridge.openInMemory());
    } catch (Exception e) {
      throw new StoolapException("Failed to open in-memory: " + e.getMessage(), e);
    }
  }

  /** Another handle on the same engine, closed independently of this one. */
  public StoolapDB cloneHandle() throws StoolapException {
    return handle.shared(p -> new StoolapDB(NativeBridge.cloneDb(p)));
  }

  /** Executes a statement; returns the rows affected. */
  public long execute(String sql) throws StoolapException {
    return handle.shared(p -> NativeBridge.execParams(p, sql, null, 0));
  }

  /** Executes with positional parameters ({@code $1} or {@code ?}); returns the rows affected. */
  public long execute(String sql, Object... params) throws StoolapException {
    return handle.shared(p -> NativeBridge.execParams(p, sql, ParamEncoder.encode(params), 0));
  }

  /** Executes with named parameters ({@code :name}); returns the rows affected. */
  public long execute(String sql, Map<String, ?> params) throws StoolapException {
    return handle.shared(p -> NativeBridge.execParams(p, sql, ParamEncoder.encode(params), 0));
  }

  /** Runs a query and returns its whole result. */
  public BulkDecoder.Result query(String sql) throws StoolapException {
    return BulkDecoder.decode(handle.shared(p -> NativeBridge.queryParams(p, sql, null)));
  }

  /** Queries with positional parameters. */
  public BulkDecoder.Result query(String sql, Object... params) throws StoolapException {
    return BulkDecoder.decode(
        handle.shared(p -> NativeBridge.queryParams(p, sql, ParamEncoder.encode(params))));
  }

  /** Queries with named parameters ({@code :name}). */
  public BulkDecoder.Result query(String sql, Map<String, ?> params) throws StoolapException {
    return BulkDecoder.decode(
        handle.shared(p -> NativeBridge.queryParams(p, sql, ParamEncoder.encode(params))));
  }

  /** Queries with positional parameters, reading the result in chunks. */
  public StoolapCursor cursor(String sql, Object... params) throws StoolapException {
    return new StoolapCursor(
        handle.shared(
            p ->
                new ChunkReader(
                    NativeBridge.queryCursor(p, sql, ParamEncoder.encode(params), CHUNK_SIZE, 0),
                    CHUNK_SIZE)));
  }

  /** Queries with named parameters, reading the result in chunks. */
  public StoolapCursor cursor(String sql, Map<String, ?> params) throws StoolapException {
    return new StoolapCursor(
        handle.shared(
            p ->
                new ChunkReader(
                    NativeBridge.queryCursor(p, sql, ParamEncoder.encode(params), CHUNK_SIZE, 0),
                    CHUNK_SIZE)));
  }

  /** Prepares a statement that runs on its compiled plan. */
  public StoolapStmt prepare(String sql) throws StoolapException {
    return handle.shared(p -> new StoolapStmt(NativeBridge.prepare(p, sql), sql, this));
  }

  /** Begins a READ COMMITTED transaction. */
  public StoolapTx begin() throws StoolapException {
    return begin(false);
  }

  /** Begins a transaction, with SNAPSHOT isolation when {@code snapshot} is true. */
  public StoolapTx begin(boolean snapshot) throws StoolapException {
    return handle.shared(p -> new StoolapTx(NativeBridge.begin(p, snapshot ? 1 : 0)));
  }

  /**
   * The native handle. Internal: for the JDBC driver, not part of the public API.
   *
   * @hidden
   */
  public NativeHandle handle() {
    return handle;
  }

  public static String version() {
    return NativeBridge.version();
  }

  /** Closes the handle once the statements running on it finish. Idempotent. */
  @Override
  public void close() {
    handle.close();
  }

  public boolean isClosed() {
    return handle.isClosed();
  }
}
