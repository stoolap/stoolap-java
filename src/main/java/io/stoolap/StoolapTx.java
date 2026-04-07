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

  public void commit() throws StoolapException {
    checkEnded();
    ended = true;
    try {
      NativeBridge.txCommit(ptr);
    } catch (Exception e) {
      throw new StoolapException("Commit failed: " + e.getMessage(), e);
    }
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
    if (!ended) {
      rollback();
    }
    NativeBridge.txClose(ptr);
    ptr = 0;
  }

  private void checkEnded() throws StoolapException {
    if (ended) throw new StoolapException("Transaction has already ended");
  }
}
