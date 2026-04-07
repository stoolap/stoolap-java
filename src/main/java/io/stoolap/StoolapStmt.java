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

/** A prepared SQL statement backed by a Rust {@code Box<Statement>}. */
public class StoolapStmt implements AutoCloseable {

  private long ptr;
  private final String sql;
  private boolean closed;

  StoolapStmt(long ptr, String sql) {
    this.ptr = ptr;
    this.sql = sql;
  }

  public long execute(Object... params) throws StoolapException {
    checkClosed();
    try {
      return NativeBridge.stmtExec(ptr, ParamEncoder.encode(params));
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public BulkDecoder.Result query(Object... params) throws StoolapException {
    checkClosed();
    try {
      byte[] buf = NativeBridge.stmtQuery(ptr, ParamEncoder.encode(params));
      return BulkDecoder.decode(buf);
    } catch (Exception e) {
      throw new StoolapException(e.getMessage(), e);
    }
  }

  public String getSql() {
    return sql;
  }

  public long ptr() {
    return ptr;
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      NativeBridge.stmtClose(ptr);
      ptr = 0;
    }
  }

  private void checkClosed() throws StoolapException {
    if (closed) throw new StoolapException("Statement is closed");
  }
}
