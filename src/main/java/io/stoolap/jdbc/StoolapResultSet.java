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
package io.stoolap.jdbc;

import io.stoolap.internal.ChunkReader;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Forward-only, read-only JDBC ResultSet.
 *
 * <p>A query result streams from the engine in chunks (the statement's fetch size, 1024 rows by
 * default), each crossing JNI in one call. Metadata results are built in memory.
 *
 * <p>TIMESTAMP values are instants. {@link #getObject(int)} returns them as {@link Timestamp};
 * {@link #getDate} and {@link #getTime} read their UTC date and time, which is how {@code
 * setDate}/{@code setTime} text is stored.
 */
public class StoolapResultSet implements ResultSet {

  /** Where the rows come from: a native cursor, or rows already in memory. */
  private interface Rows {
    Object[] next() throws SQLException;

    void close();
  }

  private final StoolapStatement stmt;
  private final String[] columnNames;
  private final byte[] columnTags;
  private final Rows source;
  private final long maxRows;
  private Map<String, Integer> labels;
  private Object[] current;
  private Object[] ahead;
  private boolean aheadRead;
  private long rowNum;
  private boolean afterLast;
  private boolean closed;
  private boolean wasNull;
  private int fetchSize;
  private ResultSetMetaData cachedMeta;

  /** A result streamed from a native cursor, read a chunk at a time. */
  StoolapResultSet(StoolapStatement stmt, ChunkReader reader, long maxRows) {
    this.stmt = stmt;
    this.columnNames = reader.columnNames();
    this.columnTags = reader.columnTags();
    this.maxRows = maxRows;
    this.source =
        new Rows() {
          private List<Object[]> chunk = List.of();
          private int pos;

          @Override
          public Object[] next() throws SQLException {
            if (pos == chunk.size()) {
              chunk = reader.nextChunk();
              pos = 0;
              if (chunk.isEmpty()) return null;
            }
            return chunk.get(pos++);
          }

          @Override
          public void close() {
            reader.close();
          }
        };
  }

  /** A result held in memory, such as a metadata result; {@code stmt} may be null. */
  StoolapResultSet(StoolapStatement stmt, String[] columnNames, List<Object[]> rows) {
    this.stmt = stmt;
    this.columnNames = columnNames;
    this.columnTags = tagsOf(columnNames.length, rows);
    this.maxRows = 0;
    var it = rows.iterator();
    this.source =
        new Rows() {
          @Override
          public Object[] next() {
            return it.hasNext() ? it.next() : null;
          }

          @Override
          public void close() {}
        };
  }

  /** The packed-format type tag of each column's first non-null value in {@code rows}. */
  private static byte[] tagsOf(int columns, List<Object[]> rows) {
    byte[] tags = new byte[columns];
    for (Object[] row : rows) {
      for (int c = 0; c < columns; c++) {
        if (tags[c] != 0 || row[c] == null) continue;
        Object v = row[c];
        tags[c] =
            v instanceof Long || v instanceof Integer || v instanceof Short
                ? (byte) 1
                : v instanceof Double
                    ? (byte) 2
                    : v instanceof Boolean
                        ? (byte) 4
                        : v instanceof Instant
                            ? (byte) 5
                            : v instanceof float[] ? (byte) 7 : (byte) 3;
      }
    }
    return tags;
  }

  // ---- Navigation ----

  private Object[] read() throws SQLException {
    if (aheadRead) {
      aheadRead = false;
      Object[] r = ahead;
      ahead = null;
      return r;
    }
    return source.next();
  }

  private Object[] peek() throws SQLException {
    if (!aheadRead) {
      ahead = source.next();
      aheadRead = true;
    }
    return ahead;
  }

  private boolean limitReached() {
    return maxRows > 0 && rowNum >= maxRows;
  }

  @Override
  public boolean next() throws SQLException {
    checkClosed();
    if (afterLast) return false;
    Object[] row = limitReached() ? null : read();
    if (row == null) {
      current = null;
      afterLast = true;
      source.close();
      return false;
    }
    current = row;
    rowNum++;
    return true;
  }

  @Override
  public boolean isBeforeFirst() throws SQLException {
    checkClosed();
    return rowNum == 0 && !afterLast && peek() != null;
  }

  @Override
  public boolean isAfterLast() throws SQLException {
    checkClosed();
    return afterLast && rowNum > 0;
  }

  @Override
  public boolean isFirst() throws SQLException {
    checkClosed();
    return current != null && rowNum == 1;
  }

  @Override
  public boolean isLast() throws SQLException {
    checkClosed();
    return current != null && (limitReached() || peek() == null);
  }

  @Override
  public int getRow() throws SQLException {
    checkClosed();
    return current != null ? (int) rowNum : 0;
  }

  @Override
  public void close() throws SQLException {
    if (closed) return;
    closed = true;
    current = null;
    ahead = null;
    source.close();
    if (stmt != null) stmt.resultSetClosed(this);
  }

  @Override
  public boolean isClosed() throws SQLException {
    return closed;
  }

  @Override
  public Statement getStatement() throws SQLException {
    return stmt;
  }

  @Override
  public int getHoldability() throws SQLException {
    return HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public int getFetchSize() throws SQLException {
    return fetchSize;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    if (rows < 0) throw new SQLException("fetch size must not be negative", "22023");
    fetchSize = rows;
  }

  @Override
  public boolean wasNull() throws SQLException {
    return wasNull;
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    checkClosed();
    if (cachedMeta == null) cachedMeta = new StoolapResultSetMetaData(columnNames, columnTags);
    return cachedMeta;
  }

  // ---- Column access ----

  private Object getColumn(int columnIndex) throws SQLException {
    checkClosed();
    if (current == null) throw new SQLException("No current row", "24000");
    if (columnIndex < 1 || columnIndex > columnNames.length) {
      throw new SQLException("Column index out of range: " + columnIndex, "22023");
    }
    Object val = current[columnIndex - 1];
    wasNull = val == null;
    return val;
  }

  private int findColumn0(String label) throws SQLException {
    if (labels == null) {
      Map<String, Integer> m = new HashMap<>(columnNames.length * 2);
      for (int i = columnNames.length - 1; i >= 0; i--) {
        m.put(columnNames[i].toLowerCase(Locale.ROOT), i + 1); // the first match wins
      }
      labels = m;
    }
    Integer i = label == null ? null : labels.get(label.toLowerCase(Locale.ROOT));
    if (i == null) throw new SQLException("Column not found: " + label, "42703");
    return i;
  }

  @Override
  public int findColumn(String columnLabel) throws SQLException {
    checkClosed();
    return findColumn0(columnLabel);
  }

  private static SQLException cannotConvert(Object val, String to) {
    String shown = val instanceof float[] f ? Arrays.toString(f) : String.valueOf(val);
    return new SQLException("Cannot convert " + shown + " to " + to, "22018");
  }

  private static SQLException outOfRange(Object val, String to) {
    return new SQLException(val + " is out of the " + to + " range", "22003");
  }

  @Override
  public String getString(int columnIndex) throws SQLException {
    Object val = getColumn(columnIndex);
    if (val == null) return null;
    if (val instanceof String s) return s;
    if (val instanceof float[] v) return Arrays.toString(v);
    return val.toString();
  }

  @Override
  public boolean getBoolean(int columnIndex) throws SQLException {
    Object val = getColumn(columnIndex);
    if (val == null) return false;
    if (val instanceof Boolean b) return b;
    if (val instanceof Long l) return l != 0;
    if (val instanceof Double d) return d != 0;
    if (val instanceof String s) {
      switch (s.trim().toLowerCase(Locale.ROOT)) {
        case "true", "t", "1", "yes", "y", "on":
          return true;
        case "false", "f", "0", "no", "n", "off":
          return false;
        default:
          throw cannotConvert(val, "BOOLEAN");
      }
    }
    throw cannotConvert(val, "BOOLEAN");
  }

  private long longValue(Object val, String to) throws SQLException {
    if (val instanceof Long l) return l;
    if (val instanceof Double d) {
      if (Double.isNaN(d) || d < -0x1p63 || d >= 0x1p63) throw outOfRange(val, to);
      return d.longValue();
    }
    if (val instanceof Boolean b) return b ? 1 : 0;
    if (val instanceof String s) {
      String t = s.trim();
      try {
        return Long.parseLong(t);
      } catch (NumberFormatException e) {
        try {
          return new BigDecimal(t).setScale(0, RoundingMode.DOWN).longValueExact();
        } catch (NumberFormatException e2) {
          throw cannotConvert(val, to);
        } catch (ArithmeticException e2) {
          throw outOfRange(val, to);
        }
      }
    }
    throw cannotConvert(val, to);
  }

  private long ranged(int columnIndex, long min, long max, String to) throws SQLException {
    Object val = getColumn(columnIndex);
    if (val == null) return 0;
    long v = longValue(val, to);
    if (v < min || v > max) throw outOfRange(val, to);
    return v;
  }

  @Override
  public byte getByte(int columnIndex) throws SQLException {
    return (byte) ranged(columnIndex, Byte.MIN_VALUE, Byte.MAX_VALUE, "TINYINT");
  }

  @Override
  public short getShort(int columnIndex) throws SQLException {
    return (short) ranged(columnIndex, Short.MIN_VALUE, Short.MAX_VALUE, "SMALLINT");
  }

  @Override
  public int getInt(int columnIndex) throws SQLException {
    return (int) ranged(columnIndex, Integer.MIN_VALUE, Integer.MAX_VALUE, "INTEGER");
  }

  @Override
  public long getLong(int columnIndex) throws SQLException {
    Object val = getColumn(columnIndex);
    return val == null ? 0 : longValue(val, "BIGINT");
  }

  private static double doubleValue(Object val) throws SQLException {
    if (val instanceof Double d) return d;
    if (val instanceof Long l) return l.doubleValue();
    if (val instanceof Boolean b) return b ? 1 : 0;
    if (val instanceof String s) {
      try {
        return Double.parseDouble(s.trim());
      } catch (NumberFormatException e) {
        throw cannotConvert(val, "DOUBLE");
      }
    }
    throw cannotConvert(val, "DOUBLE");
  }

  @Override
  public float getFloat(int columnIndex) throws SQLException {
    Object val = getColumn(columnIndex);
    if (val == null) return 0;
    double d = doubleValue(val);
    if (Double.isFinite(d) && Math.abs(d) > Float.MAX_VALUE) throw outOfRange(val, "REAL");
    return (float) d;
  }

  @Override
  public double getDouble(int columnIndex) throws SQLException {
    Object val = getColumn(columnIndex);
    return val == null ? 0 : doubleValue(val);
  }

  @Override
  public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    Object val = getColumn(columnIndex);
    if (val == null) return null;
    if (val instanceof Long l) return BigDecimal.valueOf(l);
    if (val instanceof Double d) {
      if (!Double.isFinite(d)) throw outOfRange(val, "DECIMAL");
      return BigDecimal.valueOf(d);
    }
    if (val instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
    if (val instanceof String s) {
      try {
        return new BigDecimal(s.trim());
      } catch (NumberFormatException e) {
        throw cannotConvert(val, "DECIMAL");
      }
    }
    throw cannotConvert(val, "DECIMAL");
  }

  @Override
  @SuppressWarnings("deprecation")
  public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
    BigDecimal d = getBigDecimal(columnIndex);
    return d == null ? null : d.setScale(scale, RoundingMode.HALF_UP);
  }

  @Override
  public byte[] getBytes(int columnIndex) throws SQLException {
    Object val = getColumn(columnIndex);
    if (val == null) return null;
    if (val instanceof String s) return s.getBytes(StandardCharsets.UTF_8);
    throw cannotConvert(val, "BINARY");
  }

  // ---- Dates and times ----

  /** The local date and time a value names, read at {@code zone} when it is an instant. */
  private static LocalDateTime localDateTime(Object val, ZoneId zone, String to)
      throws SQLException {
    if (val instanceof Instant inst) return LocalDateTime.ofInstant(inst, zone);
    if (val instanceof String s) {
      String t = s.trim();
      try {
        return LocalDateTime.parse(t.replace(' ', 'T'));
      } catch (DateTimeException e) {
        // try the forms below
      }
      try {
        return LocalDate.parse(t).atStartOfDay();
      } catch (DateTimeException e) {
        // try the forms below
      }
      try {
        return LocalDateTime.ofInstant(OffsetDateTime.parse(t.replace(' ', 'T')).toInstant(), zone);
      } catch (DateTimeException e) {
        // try the forms below
      }
      try {
        return LocalTime.parse(t).atDate(LocalDate.EPOCH);
      } catch (DateTimeException e) {
        throw new SQLException("Cannot convert " + val + " to " + to, "22007");
      }
    }
    throw cannotConvert(val, to);
  }

  /** The instant a value names; text without an offset is read at {@code zone}. */
  private static Instant instant(Object val, ZoneId zone) throws SQLException {
    if (val instanceof Instant inst) return inst;
    if (val instanceof String s) {
      try {
        return OffsetDateTime.parse(s.trim().replace(' ', 'T')).toInstant();
      } catch (DateTimeException e) {
        return localDateTime(val, zone, "TIMESTAMP").atZone(zone).toInstant();
      }
    }
    throw cannotConvert(val, "TIMESTAMP");
  }

  private static ZoneId zoneOf(Calendar cal) {
    return cal != null ? cal.getTimeZone().toZoneId() : ZoneOffset.UTC;
  }

  @Override
  public Date getDate(int columnIndex) throws SQLException {
    return getDate(columnIndex, (Calendar) null);
  }

  @Override
  public Date getDate(int columnIndex, Calendar cal) throws SQLException {
    Object val = getColumn(columnIndex);
    if (val == null) return null;
    return Date.valueOf(localDateTime(val, zoneOf(cal), "DATE").toLocalDate());
  }

  @Override
  public Time getTime(int columnIndex) throws SQLException {
    return getTime(columnIndex, (Calendar) null);
  }

  @Override
  public Time getTime(int columnIndex, Calendar cal) throws SQLException {
    Object val = getColumn(columnIndex);
    if (val == null) return null;
    return Time.valueOf(localDateTime(val, zoneOf(cal), "TIME").toLocalTime());
  }

  @Override
  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    Object val = getColumn(columnIndex);
    if (val == null) return null;
    if (val instanceof Instant inst) return Timestamp.from(inst);
    if (val instanceof String)
      return Timestamp.valueOf(localDateTime(val, ZoneOffset.UTC, "TIMESTAMP"));
    throw cannotConvert(val, "TIMESTAMP");
  }

  @Override
  public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
    Object val = getColumn(columnIndex);
    if (val == null) return null;
    return Timestamp.from(instant(val, zoneOf(cal)));
  }

  // ---- Objects ----

  @Override
  public Object getObject(int columnIndex) throws SQLException {
    Object val = getColumn(columnIndex);
    return val instanceof Instant inst ? Timestamp.from(inst) : val;
  }

  @Override
  public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
    return getObject(columnIndex);
  }

  @Override
  public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
    if (type == null) throw new SQLException("type must not be null", "22023");
    Object val = getColumn(columnIndex);
    if (val == null) return null;
    Object out;
    if (type == String.class) out = getString(columnIndex);
    else if (type == Long.class || type == long.class) out = getLong(columnIndex);
    else if (type == Integer.class || type == int.class) out = getInt(columnIndex);
    else if (type == Short.class || type == short.class) out = getShort(columnIndex);
    else if (type == Byte.class || type == byte.class) out = getByte(columnIndex);
    else if (type == Double.class || type == double.class) out = getDouble(columnIndex);
    else if (type == Float.class || type == float.class) out = getFloat(columnIndex);
    else if (type == Boolean.class || type == boolean.class) out = getBoolean(columnIndex);
    else if (type == BigDecimal.class) out = getBigDecimal(columnIndex);
    else if (type == BigInteger.class) out = getBigDecimal(columnIndex).toBigInteger();
    else if (type == Timestamp.class || type == java.util.Date.class)
      out = getTimestamp(columnIndex);
    else if (type == Date.class) out = getDate(columnIndex);
    else if (type == Time.class) out = getTime(columnIndex);
    else if (type == Instant.class) out = instant(val, ZoneOffset.UTC);
    else if (type == LocalDateTime.class) out = localDateTime(val, ZoneOffset.UTC, "TIMESTAMP");
    else if (type == LocalDate.class)
      out = localDateTime(val, ZoneOffset.UTC, "DATE").toLocalDate();
    else if (type == LocalTime.class)
      out = localDateTime(val, ZoneOffset.UTC, "TIME").toLocalTime();
    else if (type == OffsetDateTime.class)
      out = instant(val, ZoneOffset.UTC).atOffset(ZoneOffset.UTC);
    else if (type == ZonedDateTime.class) out = instant(val, ZoneOffset.UTC).atZone(ZoneOffset.UTC);
    else if (type == byte[].class) out = getBytes(columnIndex);
    else if (type == Object.class) out = getObject(columnIndex);
    else if (type.isInstance(val)) out = val;
    else throw cannotConvert(val, type.getName());
    @SuppressWarnings("unchecked")
    T t = (T) out;
    return t;
  }

  @Override
  public Reader getCharacterStream(int columnIndex) throws SQLException {
    String s = getString(columnIndex);
    return s == null ? null : new StringReader(s);
  }

  @Override
  public Reader getNCharacterStream(int columnIndex) throws SQLException {
    return getCharacterStream(columnIndex);
  }

  @Override
  public String getNString(int columnIndex) throws SQLException {
    return getString(columnIndex);
  }

  // ---- By column label ----

  @Override
  public String getString(String columnLabel) throws SQLException {
    return getString(findColumn0(columnLabel));
  }

  @Override
  public boolean getBoolean(String columnLabel) throws SQLException {
    return getBoolean(findColumn0(columnLabel));
  }

  @Override
  public byte getByte(String columnLabel) throws SQLException {
    return getByte(findColumn0(columnLabel));
  }

  @Override
  public short getShort(String columnLabel) throws SQLException {
    return getShort(findColumn0(columnLabel));
  }

  @Override
  public int getInt(String columnLabel) throws SQLException {
    return getInt(findColumn0(columnLabel));
  }

  @Override
  public long getLong(String columnLabel) throws SQLException {
    return getLong(findColumn0(columnLabel));
  }

  @Override
  public float getFloat(String columnLabel) throws SQLException {
    return getFloat(findColumn0(columnLabel));
  }

  @Override
  public double getDouble(String columnLabel) throws SQLException {
    return getDouble(findColumn0(columnLabel));
  }

  @Override
  @SuppressWarnings("deprecation")
  public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
    return getBigDecimal(findColumn0(columnLabel), scale);
  }

  @Override
  public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
    return getBigDecimal(findColumn0(columnLabel));
  }

  @Override
  public byte[] getBytes(String columnLabel) throws SQLException {
    return getBytes(findColumn0(columnLabel));
  }

  @Override
  public Date getDate(String columnLabel) throws SQLException {
    return getDate(findColumn0(columnLabel));
  }

  @Override
  public Date getDate(String columnLabel, Calendar cal) throws SQLException {
    return getDate(findColumn0(columnLabel), cal);
  }

  @Override
  public Time getTime(String columnLabel) throws SQLException {
    return getTime(findColumn0(columnLabel));
  }

  @Override
  public Time getTime(String columnLabel, Calendar cal) throws SQLException {
    return getTime(findColumn0(columnLabel), cal);
  }

  @Override
  public Timestamp getTimestamp(String columnLabel) throws SQLException {
    return getTimestamp(findColumn0(columnLabel));
  }

  @Override
  public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
    return getTimestamp(findColumn0(columnLabel), cal);
  }

  @Override
  public Object getObject(String columnLabel) throws SQLException {
    return getObject(findColumn0(columnLabel));
  }

  @Override
  public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
    return getObject(findColumn0(columnLabel));
  }

  @Override
  public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
    return getObject(findColumn0(columnLabel), type);
  }

  @Override
  public Reader getCharacterStream(String columnLabel) throws SQLException {
    return getCharacterStream(findColumn0(columnLabel));
  }

  @Override
  public Reader getNCharacterStream(String columnLabel) throws SQLException {
    return getCharacterStream(findColumn0(columnLabel));
  }

  @Override
  public String getNString(String columnLabel) throws SQLException {
    return getString(findColumn0(columnLabel));
  }

  private void checkClosed() throws SQLException {
    if (closed) throw new SQLException("ResultSet is closed", "24000");
  }

  // ---- Unsupported: scrolling, updates, LOBs and streams ----

  @Override
  public int getType() throws SQLException {
    return TYPE_FORWARD_ONLY;
  }

  @Override
  public int getConcurrency() throws SQLException {
    return CONCUR_READ_ONLY;
  }

  @Override
  public int getFetchDirection() throws SQLException {
    return FETCH_FORWARD;
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {}

  @Override
  public InputStream getAsciiStream(int columnIndex) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @SuppressWarnings("deprecation")
  public InputStream getUnicodeStream(int columnIndex) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public InputStream getBinaryStream(int columnIndex) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public InputStream getAsciiStream(String columnLabel) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @SuppressWarnings("deprecation")
  public InputStream getUnicodeStream(String columnLabel) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public InputStream getBinaryStream(String columnLabel) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    return null;
  }

  @Override
  public void clearWarnings() throws SQLException {}

  @Override
  public String getCursorName() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void beforeFirst() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void afterLast() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public boolean first() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public boolean last() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public boolean absolute(int row) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public boolean relative(int rows) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public boolean previous() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public boolean rowUpdated() throws SQLException {
    return false;
  }

  @Override
  public boolean rowInserted() throws SQLException {
    return false;
  }

  @Override
  public boolean rowDeleted() throws SQLException {
    return false;
  }

  @Override
  public void updateNull(int columnIndex) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBoolean(int columnIndex, boolean x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateByte(int columnIndex, byte x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateShort(int columnIndex, short x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateInt(int columnIndex, int x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateLong(int columnIndex, long x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateFloat(int columnIndex, float x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateDouble(int columnIndex, double x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateString(int columnIndex, String x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBytes(int columnIndex, byte[] x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateDate(int columnIndex, Date x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateTime(int columnIndex, Time x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateObject(int columnIndex, Object x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNull(String columnLabel) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBoolean(String columnLabel, boolean x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateByte(String columnLabel, byte x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateShort(String columnLabel, short x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateInt(String columnLabel, int x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateLong(String columnLabel, long x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateFloat(String columnLabel, float x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateDouble(String columnLabel, double x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateString(String columnLabel, String x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBytes(String columnLabel, byte[] x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateDate(String columnLabel, Date x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateTime(String columnLabel, Time x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, int length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, int length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateObject(String columnLabel, Object x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void insertRow() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateRow() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void deleteRow() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void refreshRow() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void cancelRowUpdates() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void moveToInsertRow() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void moveToCurrentRow() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public Ref getRef(int columnIndex) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public Blob getBlob(int columnIndex) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public Clob getClob(int columnIndex) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public Array getArray(int columnIndex) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public Ref getRef(String columnLabel) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public Blob getBlob(String columnLabel) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public Clob getClob(String columnLabel) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public Array getArray(String columnLabel) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public URL getURL(int columnIndex) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public URL getURL(String columnLabel) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateRef(int columnIndex, Ref x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateRef(String columnLabel, Ref x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBlob(int columnIndex, Blob x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBlob(String columnLabel, Blob x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateClob(int columnIndex, Clob x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateClob(String columnLabel, Clob x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateArray(int columnIndex, Array x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateArray(String columnLabel, Array x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public RowId getRowId(int columnIndex) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public RowId getRowId(String columnLabel) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateRowId(int columnIndex, RowId x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateRowId(String columnLabel, RowId x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNString(int columnIndex, String nString) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNString(String columnLabel, String nString) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public NClob getNClob(int columnIndex) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public NClob getNClob(String columnLabel) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public SQLXML getSQLXML(int columnIndex) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public SQLXML getSQLXML(String columnLabel) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateClob(int columnIndex, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateClob(String columnLabel, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) return iface.cast(this);
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass());
  }
}
