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
import io.stoolap.internal.NativeBridge;
import io.stoolap.internal.ParamEncoder;

/**
 * A connection to a Stoolap database.
 *
 * <p>Each handle maps to a Rust {@code Box<Database>}. Use {@link #cloneHandle()} for
 * multi-threaded use (clones share the same engine).
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

  private long ptr;
  private volatile boolean closed;

  StoolapDB(long ptr) {
    this.ptr = ptr;
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

  public StoolapDB cloneHandle() throws StoolapException {
    checkClosed();
    return new StoolapDB(NativeBridge.cloneDb(ptr));
  }

  public long execute(String sql) throws StoolapException {
    checkClosed();
    try {
      return NativeBridge.exec(ptr, sql);
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public long execute(String sql, Object... params) throws StoolapException {
    checkClosed();
    if (params == null || params.length == 0) return execute(sql);
    try {
      return NativeBridge.execParams(ptr, sql, ParamEncoder.encode(params));
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public BulkDecoder.Result query(String sql) throws StoolapException {
    checkClosed();
    try {
      byte[] buf = NativeBridge.query(ptr, sql);
      return BulkDecoder.decode(buf);
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public BulkDecoder.Result query(String sql, Object... params) throws StoolapException {
    checkClosed();
    if (params == null || params.length == 0) return query(sql);
    try {
      byte[] buf = NativeBridge.queryParams(ptr, sql, ParamEncoder.encode(params));
      return BulkDecoder.decode(buf);
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public StoolapStmt prepare(String sql) throws StoolapException {
    checkClosed();
    try {
      long stmtPtr = NativeBridge.prepare(ptr, sql);
      return new StoolapStmt(stmtPtr, sql);
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public StoolapTx begin() throws StoolapException {
    return begin(false);
  }

  public StoolapTx begin(boolean snapshot) throws StoolapException {
    checkClosed();
    try {
      long txPtr = NativeBridge.begin(ptr, snapshot ? 1 : 0);
      return new StoolapTx(txPtr);
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public long ptr() {
    return ptr;
  }

  public static String version() {
    return NativeBridge.version();
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      NativeBridge.closeDb(ptr);
      ptr = 0;
    }
  }

  public boolean isClosed() {
    return closed;
  }

  private void checkClosed() throws StoolapException {
    if (closed) throw new StoolapException("Database is closed");
  }
}
