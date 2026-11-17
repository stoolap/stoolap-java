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
package io.stoolap.internal;

import io.stoolap.StoolapException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Reads a native result chunk by chunk. Built from the buffer a {@code *QueryCursor} native call
 * returns: {@code [cursor_handle:i64][first chunk]}, where a zero handle means the first chunk
 * holds the whole result.
 *
 * <p>Internal: shared by {@link io.stoolap.StoolapCursor} and the JDBC result set.
 */
public final class ChunkReader implements AutoCloseable {

  private final NativeHandle handle;
  private final int chunkSize;
  private final String[] columnNames;
  private final long rowsAffected;
  private final byte[] columnTags;
  private List<Object[]> chunk;
  private int index;

  public ChunkReader(byte[] opened, int chunkSize) {
    long ptr = ByteBuffer.wrap(opened, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    BulkDecoder.Result first = BulkDecoder.decode(opened, 8);
    this.handle =
        ptr != 0 ? new NativeHandle(this, ptr, NativeBridge::cursorClose, "Cursor") : null;
    this.chunkSize = chunkSize;
    this.columnNames = first.columnNames();
    this.rowsAffected = first.rowsAffected();
    this.columnTags = first.columnTags();
    this.chunk = first.rows();
  }

  public String[] columnNames() {
    return columnNames;
  }

  /** Per column, the type tag of its first non-null value in the first chunk (0 if none). */
  public byte[] columnTags() {
    return columnTags;
  }

  /** The rows a statement without a result changed. */
  public long rowsAffected() {
    return rowsAffected;
  }

  /** The next row, or {@code null} when the result is exhausted. */
  public synchronized Object[] next() throws StoolapException {
    if (index == chunk.size()) {
      if (handle == null || handle.isClosed()) return null;
      fetch();
      if (chunk.isEmpty()) return null;
    }
    return chunk.get(index++);
  }

  /**
   * The rows not read yet, a chunk at a time: the rest of the current chunk, else the next chunk,
   * else an empty list. For a reader one thread owns; it takes no lock per row.
   */
  public synchronized List<Object[]> nextChunk() throws StoolapException {
    if (index == chunk.size()) {
      if (handle == null || handle.isClosed()) return List.of();
      fetch();
    }
    List<Object[]> rest = index == 0 ? chunk : chunk.subList(index, chunk.size());
    index = chunk.size();
    return rest;
  }

  private void fetch() throws StoolapException {
    try {
      chunk =
          BulkDecoder.decode(handle.exclusive(p -> NativeBridge.cursorFetch(p, chunkSize)), 0)
              .rows();
      index = 0;
      if (chunk.size() < chunkSize) handle.close();
    } catch (StoolapException e) {
      close();
      throw e;
    }
  }

  /** Releases the native result; {@link #next()} returns {@code null} from then on. */
  @Override
  public synchronized void close() {
    if (handle != null) handle.close();
    chunk = List.of();
    index = 0;
  }
}
