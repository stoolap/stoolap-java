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
import java.util.Map;

/**
 * A database transaction backed by a Rust {@code Box<ApiTransaction>}. Auto-rolls back on close if
 * not committed.
 */
public class StoolapTx implements AutoCloseable {

  private long ptr;
  private boolean ended;

  StoolapTx(long ptr) {
    this.ptr = ptr;
  }

  public long execute(String sql) throws StoolapException {
    checkEnded();
    try {
      return NativeBridge.txExec(ptr, sql);
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public long execute(String sql, Object... params) throws StoolapException {
    checkEnded();
    if (params == null || params.length == 0) return execute(sql);
    try {
      return NativeBridge.txExecParams(ptr, sql, ParamEncoder.encode(params));
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public BulkDecoder.Result query(String sql) throws StoolapException {
    checkEnded();
    try {
      byte[] buf = NativeBridge.txQuery(ptr, sql);
      return BulkDecoder.decode(buf);
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public BulkDecoder.Result query(String sql, Object... params) throws StoolapException {
    checkEnded();
    if (params == null || params.length == 0) return query(sql);
    try {
      byte[] buf = NativeBridge.txQueryParams(ptr, sql, ParamEncoder.encode(params));
      return BulkDecoder.decode(buf);
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public long execute(StoolapStmt stmt, Object... params) throws StoolapException {
    checkEnded();
    try {
      return NativeBridge.txStmtExec(ptr, stmt.ptr(), ParamEncoder.encode(params));
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public BulkDecoder.Result query(StoolapStmt stmt, Object... params) throws StoolapException {
    checkEnded();
    try {
      byte[] buf = NativeBridge.txStmtQuery(ptr, stmt.ptr(), ParamEncoder.encode(params));
      return BulkDecoder.decode(buf);
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  /** Executes with named parameters ({@code :name}); returns the rows affected. */
  public long execute(String sql, Map<String, ?> params) throws StoolapException {
    checkEnded();
    try {
      return NativeBridge.txExecParams(ptr, sql, ParamEncoder.encode(params));
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  /** Queries with named parameters ({@code :name}). */
  public BulkDecoder.Result query(String sql, Map<String, ?> params) throws StoolapException {
    checkEnded();
    try {
      return BulkDecoder.decode(NativeBridge.txQueryParams(ptr, sql, ParamEncoder.encode(params)));
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  /** Executes a prepared statement in this transaction with named parameters. */
  public long execute(StoolapStmt stmt, Map<String, ?> params) throws StoolapException {
    checkEnded();
    try {
      return NativeBridge.txStmtExec(ptr, stmt.ptr(), ParamEncoder.encode(params));
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  /** Queries a prepared statement in this transaction with named parameters. */
  public BulkDecoder.Result query(StoolapStmt stmt, Map<String, ?> params)
      throws StoolapException {
    checkEnded();
    try {
      return BulkDecoder.decode(
          NativeBridge.txStmtQuery(ptr, stmt.ptr(), ParamEncoder.encode(params)));
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  /** Queries with positional parameters, reading the result in chunks. */
  public StoolapCursor cursor(String sql, Object... params) throws StoolapException {
    checkEnded();
    try {
      return new StoolapCursor(NativeBridge.txQueryCursor(ptr, sql, ParamEncoder.encode(params)));
    } catch (StoolapException e) {
      throw e;
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  /** Queries a prepared statement in this transaction, reading the result in chunks. */
  public StoolapCursor cursor(StoolapStmt stmt, Object... params) throws StoolapException {
    checkEnded();
    try {
      return new StoolapCursor(
          NativeBridge.txStmtQueryCursor(ptr, stmt.ptr(), ParamEncoder.encode(params)));
    } catch (StoolapException e) {
      throw e;
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  /**
   * Commits. A failed commit leaves the transaction active, so it can still be rolled back.
   */
  public void commit() throws StoolapException {
    checkEnded();
    try {
      NativeBridge.txCommit(ptr);
    } catch (Exception e) {
      throw new StoolapException("Commit failed: " + e.getMessage(), e);
    }
    ended = true;
  }

  public long ptr() {
    return ptr;
  }

  public void rollback() throws StoolapException {
    if (ended) return;
    ended = true;
    try {
      NativeBridge.txRollback(ptr);
    } catch (Exception e) {
      throw new StoolapException("Rollback failed: " + e.getMessage(), e);
    }
  }

  @Override
  public void close() throws StoolapException {
    try {
      if (!ended) {
        rollback();
      }
    } finally {
      NativeBridge.txClose(ptr);
      ptr = 0;
    }
  }

  private void checkEnded() throws StoolapException {
    if (ended) throw new StoolapException("Transaction has already ended");
  }
}
