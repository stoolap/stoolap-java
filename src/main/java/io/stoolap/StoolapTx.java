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
 * A database transaction backed by a Rust {@code Transaction}. It accepts DML ({@code SELECT},
 * {@code INSERT}, {@code UPDATE}, {@code DELETE}) and savepoints; run DDL on the {@link StoolapDB}.
 *
 * <p>Calls on one transaction from several threads run one at a time. {@link #commit()} and {@link
 * #rollback()} release the native transaction; {@link #close()} rolls back a transaction that has
 * not ended.
 */
public class StoolapTx implements AutoCloseable {

  private final NativeHandle handle;
  private boolean ended;

  StoolapTx(long ptr) {
    this.handle = new NativeHandle(this, ptr, NativeBridge::txClose, "Transaction");
  }

  private synchronized <T> T run(NativeHandle.Call<T> call) throws StoolapException {
    if (ended) throw new StoolapException("Transaction has already ended", "25000");
    return handle.exclusive(call);
  }

  /** Executes a statement; returns the rows affected. */
  public long execute(String sql) throws StoolapException {
    return run(p -> NativeBridge.txExecParams(p, sql, null));
  }

  /** Executes with positional parameters; returns the rows affected. */
  public long execute(String sql, Object... params) throws StoolapException {
    return run(p -> NativeBridge.txExecParams(p, sql, ParamEncoder.encode(params)));
  }

  /** Executes with named parameters ({@code :name}); returns the rows affected. */
  public long execute(String sql, Map<String, ?> params) throws StoolapException {
    return run(p -> NativeBridge.txExecParams(p, sql, ParamEncoder.encode(params)));
  }

  /** Runs a query and returns its whole result. */
  public BulkDecoder.Result query(String sql) throws StoolapException {
    return BulkDecoder.decode(run(p -> NativeBridge.txQueryParams(p, sql, null)));
  }

  /** Queries with positional parameters. */
  public BulkDecoder.Result query(String sql, Object... params) throws StoolapException {
    return BulkDecoder.decode(
        run(p -> NativeBridge.txQueryParams(p, sql, ParamEncoder.encode(params))));
  }

  /** Queries with named parameters ({@code :name}). */
  public BulkDecoder.Result query(String sql, Map<String, ?> params) throws StoolapException {
    return BulkDecoder.decode(
        run(p -> NativeBridge.txQueryParams(p, sql, ParamEncoder.encode(params))));
  }

  /** Executes a prepared statement in this transaction; returns the rows affected. */
  public long execute(StoolapStmt stmt, Object... params) throws StoolapException {
    return run(
        p -> stmt.handle().shared(s -> NativeBridge.txStmtExec(p, s, ParamEncoder.encode(params))));
  }

  /** Executes a prepared statement in this transaction with named parameters. */
  public long execute(StoolapStmt stmt, Map<String, ?> params) throws StoolapException {
    return run(
        p -> stmt.handle().shared(s -> NativeBridge.txStmtExec(p, s, ParamEncoder.encode(params))));
  }

  /** Queries a prepared statement in this transaction. */
  public BulkDecoder.Result query(StoolapStmt stmt, Object... params) throws StoolapException {
    return BulkDecoder.decode(
        run(
            p ->
                stmt.handle()
                    .shared(s -> NativeBridge.txStmtQuery(p, s, ParamEncoder.encode(params)))));
  }

  /** Queries a prepared statement in this transaction with named parameters. */
  public BulkDecoder.Result query(StoolapStmt stmt, Map<String, ?> params) throws StoolapException {
    return BulkDecoder.decode(
        run(
            p ->
                stmt.handle()
                    .shared(s -> NativeBridge.txStmtQuery(p, s, ParamEncoder.encode(params)))));
  }

  /** Queries with positional parameters, reading the result in chunks. */
  public StoolapCursor cursor(String sql, Object... params) throws StoolapException {
    return new StoolapCursor(
        run(
            p ->
                new ChunkReader(
                    NativeBridge.txQueryCursor(
                        p, sql, ParamEncoder.encode(params), StoolapDB.CHUNK_SIZE),
                    StoolapDB.CHUNK_SIZE)));
  }

  /** Queries with named parameters, reading the result in chunks. */
  public StoolapCursor cursor(String sql, Map<String, ?> params) throws StoolapException {
    return new StoolapCursor(
        run(
            p ->
                new ChunkReader(
                    NativeBridge.txQueryCursor(
                        p, sql, ParamEncoder.encode(params), StoolapDB.CHUNK_SIZE),
                    StoolapDB.CHUNK_SIZE)));
  }

  /** Queries a prepared statement in this transaction, reading the result in chunks. */
  public StoolapCursor cursor(StoolapStmt stmt, Object... params) throws StoolapException {
    return new StoolapCursor(
        run(
            p ->
                stmt.handle()
                    .shared(
                        s ->
                            new ChunkReader(
                                NativeBridge.txStmtQueryCursor(
                                    p, s, ParamEncoder.encode(params), StoolapDB.CHUNK_SIZE),
                                StoolapDB.CHUNK_SIZE))));
  }

  /**
   * Commits and releases the transaction. A failed commit leaves the transaction active, so it can
   * still be rolled back.
   */
  public synchronized void commit() throws StoolapException {
    run(NativeBridge::txCommit);
    ended = true;
    handle.close();
  }

  /** Rolls back and releases the transaction. Does nothing once the transaction has ended. */
  public synchronized void rollback() throws StoolapException {
    if (ended) return;
    ended = true;
    try {
      handle.exclusive(NativeBridge::txRollback);
    } finally {
      handle.close();
    }
  }

  /** Whether the transaction has neither committed nor rolled back. */
  public synchronized boolean isActive() {
    return !ended;
  }

  /**
   * The native handle. Internal: for the JDBC driver, not part of the public API.
   *
   * @hidden
   */
  public NativeHandle handle() {
    return handle;
  }

  /** Rolls back a transaction that has not ended, and releases it. */
  @Override
  public synchronized void close() throws StoolapException {
    try {
      rollback();
    } finally {
      handle.close();
    }
  }
}
