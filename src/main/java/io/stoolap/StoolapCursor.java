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
import java.util.List;

/**
 * Reads a query result in chunks, so a large result never sits in memory at once.
 *
 * <pre>{@code
 * try (StoolapCursor c = db.cursor("SELECT * FROM events")) {
 *   for (Object[] row; (row = c.next()) != null; ) {
 *     ...
 *   }
 * }
 * }</pre>
 *
 * <p>Each chunk crosses JNI in one call. The cursor releases its native result once the last row
 * is read, or on {@link #close()}. A cursor opened in a transaction should be read before the
 * transaction ends. A cursor is not thread-safe.
 */
public final class StoolapCursor implements AutoCloseable {

  /** Rows fetched per JNI call. */
  private static final int CHUNK_SIZE = 1024;

  private long ptr;
  private String[] columnNames;
  private List<Object[]> chunk = List.of();
  private int index;

  StoolapCursor(long ptr) throws StoolapException {
    this.ptr = ptr;
    fetch();
  }

  /** The result's column names. */
  public String[] columnNames() {
    return columnNames;
  }

  /** The next row, or {@code null} when the result is exhausted. */
  public Object[] next() throws StoolapException {
    if (index == chunk.size()) {
      if (ptr == 0) return null;
      fetch();
      if (chunk.isEmpty()) return null;
    }
    return chunk.get(index++);
  }

  private void fetch() throws StoolapException {
    try {
      BulkDecoder.Result r = BulkDecoder.decode(NativeBridge.cursorFetch(ptr, CHUNK_SIZE));
      columnNames = r.columnNames();
      chunk = r.rows();
      index = 0;
      if (chunk.size() < CHUNK_SIZE) release();
    } catch (Exception e) {
      close();
      throw new StoolapException(e.getMessage(), e);
    }
  }

  /** Closes the cursor; {@link #next()} returns {@code null} from then on. */
  @Override
  public void close() {
    release();
    chunk = List.of();
    index = 0;
  }

  /** Frees the native result, keeping the rows already fetched. */
  private void release() {
    if (ptr != 0) {
      NativeBridge.cursorClose(ptr);
      ptr = 0;
    }
  }
}
