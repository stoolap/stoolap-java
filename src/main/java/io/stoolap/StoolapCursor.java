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

import io.stoolap.internal.ChunkReader;

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
 * <p>Each chunk of 1024 rows crosses JNI in one call. The cursor releases its native result once
 * the last row is read, or on {@link #close()}. A runtime error part way through the result is
 * raised from {@link #next()}. A cursor opened in a transaction should be read before the
 * transaction ends.
 */
public final class StoolapCursor implements AutoCloseable {

  private final ChunkReader reader;

  StoolapCursor(ChunkReader reader) {
    this.reader = reader;
  }

  /** The result's column names. */
  public String[] columnNames() {
    return reader.columnNames();
  }

  /** The next row, or {@code null} when the result is exhausted or the cursor is closed. */
  public Object[] next() throws StoolapException {
    return reader.next();
  }

  /** Closes the cursor; {@link #next()} returns {@code null} from then on. Idempotent. */
  @Override
  public void close() {
    reader.close();
  }
}
