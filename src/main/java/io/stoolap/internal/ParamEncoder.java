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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Encodes Java parameters into a packed binary format for zero-overhead JNI crossing.
 *
 * <p>Wire format for single row:
 *
 * <pre>
 * [param_count: u32 LE]
 * [for each param: type_tag:u8 + payload]
 * </pre>
 *
 * <p>Wire format for batch:
 *
 * <pre>
 * [row_count: u32 LE]
 * [param_count: u32 LE]
 * [for each row, for each param: type_tag:u8 + payload]
 * </pre>
 *
 * <p>Type tags match BulkDecoder: NULL=0, INTEGER=1, FLOAT=2, TEXT=3, BOOLEAN=4.
 */
public final class ParamEncoder {

  /** Thread-local buffer state to avoid per-call allocation. */
  private static final ThreadLocal<Buf> TL = ThreadLocal.withInitial(Buf::new);

  private ParamEncoder() {}

  public static byte[] encode(Object[] params) {
    Buf b = TL.get();
    b.reset();
    int count = params != null ? params.length : 0;
    b.writeInt(count);
    for (int i = 0; i < count; i++) {
      b.writeValue(params[i]);
    }
    return b.toArray();
  }

  public static byte[] encodeBatch(Object[][] batch) {
    Buf b = TL.get();
    b.reset();
    if (batch == null || batch.length == 0) {
      b.writeInt(0);
      b.writeInt(0);
      return b.toArray();
    }
    int rowCount = batch.length;
    int paramCount = batch[0] != null ? batch[0].length : 0;
    b.writeInt(rowCount);
    b.writeInt(paramCount);
    for (Object[] row : batch) {
      if (row != null) {
        for (int i = 0; i < paramCount; i++) {
          b.writeValue(i < row.length ? row[i] : null);
        }
      }
    }
    return b.toArray();
  }

  /** Mutable growing byte buffer, little-endian. */
  private static final class Buf {
    byte[] data = new byte[256];
    int pos = 0;

    void reset() {
      pos = 0;
    }

    void ensure(int more) {
      if (pos + more > data.length) {
        int newLen = Math.max(data.length * 2, pos + more);
        data = Arrays.copyOf(data, newLen);
      }
    }

    void writeByte(byte v) {
      ensure(1);
      data[pos++] = v;
    }

    void writeInt(int v) {
      ensure(4);
      data[pos++] = (byte) v;
      data[pos++] = (byte) (v >>> 8);
      data[pos++] = (byte) (v >>> 16);
      data[pos++] = (byte) (v >>> 24);
    }

    void writeLong(long v) {
      ensure(8);
      data[pos++] = (byte) v;
      data[pos++] = (byte) (v >>> 8);
      data[pos++] = (byte) (v >>> 16);
      data[pos++] = (byte) (v >>> 24);
      data[pos++] = (byte) (v >>> 32);
      data[pos++] = (byte) (v >>> 40);
      data[pos++] = (byte) (v >>> 48);
      data[pos++] = (byte) (v >>> 56);
    }

    void writeDouble(double v) {
      writeLong(Double.doubleToRawLongBits(v));
    }

    void writeBytes(byte[] b) {
      ensure(b.length);
      System.arraycopy(b, 0, data, pos, b.length);
      pos += b.length;
    }

    void writeString(String s) {
      byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
      writeByte((byte) 3);
      writeInt(bytes.length);
      writeBytes(bytes);
    }

    void writeValue(Object val) {
      if (val == null) {
        writeByte((byte) 0);
      } else if (val instanceof Long v) {
        writeByte((byte) 1);
        writeLong(v);
      } else if (val instanceof Integer v) {
        writeByte((byte) 1);
        writeLong(v.longValue());
      } else if (val instanceof Double v) {
        writeByte((byte) 2);
        writeDouble(v);
      } else if (val instanceof Float v) {
        writeByte((byte) 2);
        writeDouble(v.doubleValue());
      } else if (val instanceof String v) {
        writeString(v);
      } else if (val instanceof Boolean v) {
        writeByte((byte) 4);
        writeByte(v ? (byte) 1 : (byte) 0);
      } else if (val instanceof Short v) {
        writeByte((byte) 1);
        writeLong(v.longValue());
      } else if (val instanceof Byte v) {
        writeByte((byte) 1);
        writeLong(v.longValue());
      } else {
        writeString(val.toString());
      }
    }

    byte[] toArray() {
      return Arrays.copyOf(data, pos);
    }
  }
}
