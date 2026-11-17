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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the packed binary result buffer produced by the native library.
 *
 * <p>Wire format:
 *
 * <pre>
 * [column_count: u32 LE]
 * [for each column: name_len:u16 LE, name_bytes:u8[name_len]]
 * [row_count: u32 LE]
 * [for each row, for each column:
 *   type_tag: u8
 *   payload:
 *     NULL(0):      (empty)
 *     INTEGER(1):   i64 LE (8 bytes)
 *     FLOAT(2):     f64 LE (8 bytes)
 *     TEXT(3):      len:u32 LE + bytes
 *     BOOLEAN(4):   u8 (0 or 1)
 *     TIMESTAMP(5): i64 LE (8 bytes, nanos since epoch)
 *     JSON(6):      len:u32 LE + bytes
 *     VECTOR(7):    len:u32 LE + packed f32 LE
 * ]
 * </pre>
 *
 * <p>Decoded Java types: {@code null}, {@link Long}, {@link Double}, {@link String}, {@link
 * Boolean}, {@link Instant}, {@link String} (JSON text) and {@code float[]}.
 */
public final class BulkDecoder {

  /** Decoded result from a bulk fetch operation. */
  public record Result(String[] columnNames, List<Object[]> rows) {
    public int getColumnCount() {
      return columnNames.length;
    }

    public int getRowCount() {
      return rows.size();
    }
  }

  private BulkDecoder() {}

  /**
   * Decode a packed binary buffer into column names and typed row data.
   *
   * @param buf Raw bytes from the native library
   * @return Decoded result with column names and rows
   */
  public static Result decode(byte[] buf) {
    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

    int colCount = bb.getInt();
    String[] columnNames = new String[colCount];
    for (int i = 0; i < colCount; i++) {
      columnNames[i] = readUtf8(buf, bb, Short.toUnsignedInt(bb.getShort()));
    }

    int rowCount = bb.getInt();
    List<Object[]> rows = new ArrayList<>(rowCount);
    for (int r = 0; r < rowCount; r++) {
      Object[] row = new Object[colCount];
      for (int c = 0; c < colCount; c++) {
        int tag = Byte.toUnsignedInt(bb.get());
        row[c] =
            switch (tag) {
              case 0 -> null; // NULL
              case 1 -> bb.getLong(); // INTEGER
              case 2 -> bb.getDouble(); // FLOAT
              case 3, 6 -> readUtf8(buf, bb, bb.getInt()); // TEXT, JSON (as String)
              case 4 -> bb.get() != 0; // BOOLEAN
              case 5 -> { // TIMESTAMP
                long nanos = bb.getLong();
                yield Instant.ofEpochSecond(
                    Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
              }
              case 7 -> readVector(bb); // VECTOR
              default -> throw new IllegalArgumentException("Unknown type tag: " + tag);
            };
      }
      rows.add(row);
    }

    return new Result(columnNames, rows);
  }

  /** Decodes {@code len} UTF-8 bytes in place, without an intermediate copy. */
  private static String readUtf8(byte[] buf, ByteBuffer bb, int len) {
    int at = bb.position();
    bb.position(at + len);
    return new String(buf, at, len, StandardCharsets.UTF_8);
  }

  private static float[] readVector(ByteBuffer bb) {
    float[] v = new float[bb.getInt() / 4];
    for (int i = 0; i < v.length; i++) {
      v[i] = bb.getFloat();
    }
    return v;
  }
}
