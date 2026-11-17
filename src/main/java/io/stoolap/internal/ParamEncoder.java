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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Map;

/**
 * Encodes Java parameters into a packed binary format for zero-overhead JNI crossing.
 *
 * <p>Wire format for one parameter set:
 *
 * <pre>
 * [count: u32 LE]                       bit 31 set = named parameters
 * positional: [for each: value]
 * named:      [for each: name_len:u32 LE, name:utf8, value]
 * </pre>
 *
 * <p>Wire format for a batch: {@code [row_count: u32 LE]} followed by one parameter set per row.
 *
 * <p>A value is {@code type_tag:u8 + payload}: NULL=0, INTEGER=1 (i64), FLOAT=2 (f64), TEXT=3
 * (len:u32 + utf8), BOOLEAN=4 (u8), TIMESTAMP=5 (i64 nanos since the epoch), JSON=6 (len:u32 +
 * utf8), VECTOR=7 (count:u32 + f32 LE each).
 *
 * <p>Java types: {@link Long}, {@link Integer}, {@link Short}, {@link Byte} bind as INTEGER;
 * {@link Double}, {@link Float} as FLOAT; {@link String} as TEXT; {@link Boolean} as BOOLEAN;
 * {@link Instant}, {@link java.sql.Timestamp}, {@link OffsetDateTime}, {@link ZonedDateTime} and
 * {@link LocalDateTime} (read as UTC) as TIMESTAMP; {@code float[]} as VECTOR. A {@code byte[]} is
 * refused, since Stoolap has no BLOB type. Any other object binds as its {@code toString()} text.
 */
public final class ParamEncoder {

  private static final int NAMED_FLAG = 1 << 31;

  /** Thread-local buffer state to avoid per-call allocation. */
  private static final ThreadLocal<Buf> TL = ThreadLocal.withInitial(Buf::new);

  private ParamEncoder() {}

  /** Encodes positional parameters; {@code null} means none. */
  public static byte[] encode(Object[] params) {
    Buf b = TL.get();
    b.reset();
    b.writePositional(params);
    return b.toArray();
  }

  /** Encodes named parameters. A leading {@code :}, {@code @} or {@code $} on a name is ignored. */
  public static byte[] encode(Map<String, ?> params) {
    Buf b = TL.get();
    b.reset();
    b.writeNamed(params);
    return b.toArray();
  }

  /** Encodes a batch of positional parameter sets. */
  public static byte[] encodeBatch(Object[][] batch) {
    Buf b = TL.get();
    b.reset();
    int rows = batch != null ? batch.length : 0;
    b.writeInt(rows);
    for (int r = 0; r < rows; r++) {
      b.writePositional(batch[r]);
    }
    return b.toArray();
  }

  /** Nanoseconds since the epoch, refusing an instant outside the range a TIMESTAMP holds. */
  static long epochNanos(Instant t) {
    try {
      return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000_000L), t.getNano());
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          "TIMESTAMP "
              + t
              + " is outside the range 1677-09-21T00:12:43.145224192Z to"
              + " 2262-04-11T23:47:16.854775807Z");
    }
  }

  /** Mutable growing byte buffer, little-endian. */
  private static final class Buf {
    byte[] data = new byte[256];
    int pos = 0;

    void reset() {
      if (data.length > 1 << 20) data = new byte[256];
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

    void writeUtf8(String s) {
      byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
      writeInt(bytes.length);
      writeBytes(bytes);
    }

    void writeTimestamp(Instant t) {
      writeByte((byte) 5);
      writeLong(epochNanos(t));
    }

    void writePositional(Object[] params) {
      int count = params != null ? params.length : 0;
      writeInt(count);
      for (int i = 0; i < count; i++) {
        writeValue(params[i]);
      }
    }

    void writeNamed(Map<String, ?> params) {
      writeInt(params.size() | NAMED_FLAG);
      for (Map.Entry<String, ?> e : params.entrySet()) {
        if (e.getKey() == null) throw new IllegalArgumentException("parameter name is null");
        writeUtf8(e.getKey());
        writeValue(e.getValue());
      }
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
        writeByte((byte) 3);
        writeUtf8(v);
      } else if (val instanceof Boolean v) {
        writeByte((byte) 4);
        writeByte(v ? (byte) 1 : (byte) 0);
      } else if (val instanceof Short v) {
        writeByte((byte) 1);
        writeLong(v.longValue());
      } else if (val instanceof Byte v) {
        writeByte((byte) 1);
        writeLong(v.longValue());
      } else if (val instanceof java.sql.Timestamp v) {
        writeTimestamp(v.toInstant());
      } else if (val instanceof Instant v) {
        writeTimestamp(v);
      } else if (val instanceof OffsetDateTime v) {
        writeTimestamp(v.toInstant());
      } else if (val instanceof ZonedDateTime v) {
        writeTimestamp(v.toInstant());
      } else if (val instanceof LocalDateTime v) {
        writeTimestamp(v.toInstant(ZoneOffset.UTC));
      } else if (val instanceof float[] v) {
        writeByte((byte) 7);
        writeInt(v.length);
        ensure(v.length * 4);
        for (float f : v) {
          int bits = Float.floatToRawIntBits(f);
          data[pos++] = (byte) bits;
          data[pos++] = (byte) (bits >>> 8);
          data[pos++] = (byte) (bits >>> 16);
          data[pos++] = (byte) (bits >>> 24);
        }
      } else if (val instanceof byte[]) {
        throw new IllegalArgumentException(
            "byte[] parameters are not supported: Stoolap has no BLOB type");
      } else {
        writeByte((byte) 3);
        writeUtf8(val.toString());
      }
    }

    byte[] toArray() {
      return Arrays.copyOf(data, pos);
    }
  }
}
