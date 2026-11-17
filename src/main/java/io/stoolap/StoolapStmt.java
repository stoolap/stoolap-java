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
 * A prepared statement backed by a Rust {@code stoolap::api::Statement}. It runs on its compiled
 * plan, on the database and inside a {@link StoolapTx}. It is thread-safe, and {@link #close()}
 * waits for the runs in flight. A statement whose database is closed raises an error when run.
 */
public class StoolapStmt implements AutoCloseable {

  private final NativeHandle handle;
  private final String sql;
  private final StoolapDB db;

  StoolapStmt(long ptr, String sql, StoolapDB db) {
    this.handle = new NativeHandle(this, ptr, NativeBridge::stmtClose, "Statement");
    this.sql = sql;
    this.db = db;
  }

  /** Executes with positional parameters; returns the rows affected. */
  public long execute(Object... params) throws StoolapException {
    return handle.shared(p -> NativeBridge.stmtExec(p, ParamEncoder.encode(params)));
  }

  /** Executes with named parameters ({@code :name}); returns the rows affected. */
  public long execute(Map<String, ?> params) throws StoolapException {
    return handle.shared(p -> NativeBridge.stmtExec(p, ParamEncoder.encode(params)));
  }

  /** Queries with positional parameters. */
  public BulkDecoder.Result query(Object... params) throws StoolapException {
    return BulkDecoder.decode(
        handle.shared(p -> NativeBridge.stmtQuery(p, ParamEncoder.encode(params))));
  }

  /** Queries with named parameters ({@code :name}). */
  public BulkDecoder.Result query(Map<String, ?> params) throws StoolapException {
    return BulkDecoder.decode(
        handle.shared(p -> NativeBridge.stmtQuery(p, ParamEncoder.encode(params))));
  }

  /** Queries with positional parameters, reading the result in chunks. */
  public StoolapCursor cursor(Object... params) throws StoolapException {
    return new StoolapCursor(
        handle.shared(
            p ->
                new ChunkReader(
                    NativeBridge.stmtQueryCursor(
                        p, ParamEncoder.encode(params), StoolapDB.CHUNK_SIZE),
                    StoolapDB.CHUNK_SIZE)));
  }

  /** Queries with named parameters, reading the result in chunks. */
  public StoolapCursor cursor(Map<String, ?> params) throws StoolapException {
    return new StoolapCursor(
        handle.shared(
            p ->
                new ChunkReader(
                    NativeBridge.stmtQueryCursor(
                        p, ParamEncoder.encode(params), StoolapDB.CHUNK_SIZE),
                    StoolapDB.CHUNK_SIZE)));
  }

  public String getSql() {
    return sql;
  }

  /** The database this statement was prepared on. */
  public StoolapDB database() {
    return db;
  }

  /**
   * The native handle. Internal: for the JDBC driver, not part of the public API.
   *
   * @hidden
   */
  public NativeHandle handle() {
    return handle;
  }

  /** Closes the statement once its runs in flight finish. Idempotent. */
  @Override
  public void close() {
    handle.close();
  }

  public boolean isClosed() {
    return handle.isClosed();
  }
}
