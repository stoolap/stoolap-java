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

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/** JDBC behaviour that tools such as Flyway, Spring and JPA rely on. */
class JdbcConformanceTest {

  private static Connection open() throws SQLException {
    return DriverManager.getConnection("jdbc:stoolap:memory://");
  }

  private static long count(Connection c, String table) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  // ---- DDL and catalog statements with auto-commit off ----

  @Test
  void ddlCommitsTheOpenTransactionAndRunsOnItsOwn() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE a (id INTEGER PRIMARY KEY)");
      c.setAutoCommit(false);
      s.executeUpdate("INSERT INTO a VALUES (1)");
      s.execute("CREATE TABLE b (id INTEGER PRIMARY KEY)"); // commits the insert
      s.executeUpdate("INSERT INTO a VALUES (2)");
      s.executeUpdate("INSERT INTO b VALUES (1)");
      c.rollback(); // undoes only what followed the CREATE
      assertEquals(1, count(c, "a"));
      assertEquals(0, count(c, "b"));
    }
  }

  @Test
  void truncateCommitsTheOpenTransactionLikeDdl() throws Exception {
    // the 0.5.1 engine refuses TRUNCATE inside a transaction
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE a (id INTEGER PRIMARY KEY)");
      s.executeUpdate("INSERT INTO a VALUES (1)");
      c.setAutoCommit(false);
      s.executeUpdate("INSERT INTO a VALUES (2)");
      s.execute("TRUNCATE TABLE a");
      s.executeUpdate("INSERT INTO a VALUES (3)");
      c.rollback();
      assertEquals(0, count(c, "a"));
    }
  }

  @Test
  void catalogStatementsLeaveTheTransactionOpen() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE a (id INTEGER PRIMARY KEY)");
      c.setAutoCommit(false);
      s.executeUpdate("INSERT INTO a VALUES (1)");
      try (ResultSet rs = s.executeQuery("SHOW TABLES")) {
        assertTrue(rs.next());
      }
      try (ResultSet rs = s.executeQuery("DESCRIBE a")) {
        assertTrue(rs.next());
      }
      try (ResultSet rs = c.getMetaData().getTables(null, null, "a", null)) {
        assertTrue(rs.next());
      }
      c.rollback();
      assertEquals(0, count(c, "a"));
    }
  }

  @Test
  void sqlCommitAndRollbackEndTheTransaction() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE a (id INTEGER PRIMARY KEY)");
      c.setAutoCommit(false);
      s.executeUpdate("INSERT INTO a VALUES (1)");
      s.execute("COMMIT");
      s.executeUpdate("INSERT INTO a VALUES (2)");
      s.execute("ROLLBACK");
      c.setAutoCommit(true);
      assertEquals(1, count(c, "a"));
    }
  }

  // ---- Statement routing ----

  @Test
  void executeReportsAResultSetExactlyWhenTheStatementReturnsColumns() throws Exception {
    Locale before = Locale.getDefault();
    Locale.setDefault(Locale.forLanguageTag("tr-TR")); // "with".toUpperCase() is "WİTH" here
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (id INTEGER PRIMARY KEY AUTO_INCREMENT, name TEXT)");
      for (String q :
          new String[] {
            "with x as (select 1 as a) select a from x",
            "explain select 1",
            "/* comment */ SELECT 1",
            "-- comment\nSELECT 1",
            "DESCRIBE t",
            "INSERT INTO t (name) VALUES ('a') RETURNING id"
          }) {
        assertTrue(s.execute(q), q);
        assertNotNull(s.getResultSet(), q);
        assertTrue(s.getResultSet().next(), q);
        assertEquals(-1, s.getUpdateCount(), q);
      }
      assertFalse(s.execute("insert into t (name) values ('b')"));
      assertNull(s.getResultSet());
      assertEquals(1, s.getUpdateCount());
      assertFalse(s.execute("update t set name = 'c'"));
      assertEquals(2, s.getUpdateCount());

      try (PreparedStatement ps = c.prepareStatement("with x as (select ? as a) select a from x")) {
        ps.setInt(1, 5);
        assertTrue(ps.execute());
        assertTrue(ps.getResultSet().next());
        assertEquals(5, ps.getResultSet().getInt(1));
      }
    } finally {
      Locale.setDefault(before);
    }
  }

  @Test
  void getMoreResultsEndsTheStandardLoop() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (id INTEGER)");
      s.execute("INSERT INTO t VALUES (1)");
      int rounds = 0;
      while (s.getMoreResults(Statement.CLOSE_CURRENT_RESULT) || s.getUpdateCount() != -1) {
        assertTrue(++rounds < 3, "the loop does not end");
      }
      assertEquals(0, rounds);
    }
  }

  @Test
  void statementBatchOfSqlText() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.addBatch("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      s.addBatch("INSERT INTO t VALUES (1), (2)");
      s.addBatch("DELETE FROM t WHERE id = 1");
      assertArrayEquals(new int[] {0, 2, 1}, s.executeBatch());
      assertEquals(1, count(c, "t"));
    }
  }

  // ---- PreparedStatement parameters ----

  @Test
  void addBatchKeepsTheParameters() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, tenant TEXT NOT NULL)");
      try (PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?, ?)")) {
        ps.setString(2, "acme");
        for (int i = 1; i <= 3; i++) {
          ps.setInt(1, i);
          ps.addBatch();
        }
        assertArrayEquals(new int[] {1, 1, 1}, ps.executeBatch());
      }
      try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM t WHERE tenant = 'acme'")) {
        rs.next();
        assertEquals(3, rs.getInt(1));
      }
    }
  }

  @Test
  void parameterGapsAndBadIndexesAreRefused() throws Exception {
    try (Connection c = open();
        PreparedStatement ps = c.prepareStatement("SELECT ?, ?")) {
      ps.setInt(2, 1);
      SQLException e = assertThrows(SQLException.class, ps::executeQuery);
      assertEquals("07001", e.getSQLState());
      SQLException bad = assertThrows(SQLException.class, () -> ps.setInt(0, 1));
      assertEquals("07009", bad.getSQLState());
      ps.setNull(1, Types.INTEGER);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertNull(rs.getObject(1));
      }
    }
  }

  @Test
  void typedParameterBinding() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (id INTEGER, ts TIMESTAMP, f FLOAT)");
      Instant when = Instant.parse("2024-05-01T08:30:00Z");
      try (PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?, ?, ?)")) {
        ps.setObject(1, BigInteger.valueOf(7));
        ps.setObject(2, java.util.Date.from(when));
        ps.setObject(3, new BigDecimal("1.25"));
        ps.executeUpdate();
        ps.setLong(1, 8);
        ps.setTimestamp(2, Timestamp.from(when));
        ps.setBigDecimal(3, new BigDecimal("1.25"));
        ps.executeUpdate();
      }
      try (ResultSet rs = s.executeQuery("SELECT id, ts, f FROM t ORDER BY id")) {
        for (long id = 7; id <= 8; id++) {
          assertTrue(rs.next());
          assertEquals(id, rs.getLong(1));
          assertEquals(when, rs.getObject(2, Instant.class));
          assertEquals(1.25, rs.getDouble(3));
        }
      }
    }
  }

  // ---- Dates and times in a non-UTC JVM ----

  @Test
  void datesAndTimesRoundTripInAnyZone() throws Exception {
    TimeZone before = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (id INTEGER, ts TIMESTAMP)");
      try (PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?, ?)")) {
        ps.setInt(1, 1);
        ps.setDate(2, Date.valueOf("2024-01-15"));
        ps.executeUpdate();
        ps.setInt(1, 2);
        ps.setTime(2, Time.valueOf("10:11:12"));
        ps.executeUpdate();
        ps.setInt(1, 3);
        ps.setTimestamp(2, Timestamp.valueOf("2024-01-15 23:30:00"));
        ps.executeUpdate();
      }
      try (ResultSet rs = s.executeQuery("SELECT ts FROM t ORDER BY id")) {
        assertTrue(rs.next());
        assertEquals(Date.valueOf("2024-01-15"), rs.getDate(1));
        assertEquals(LocalDate.of(2024, 1, 15), rs.getObject(1, LocalDate.class));
        assertTrue(rs.next());
        assertEquals(Time.valueOf("10:11:12"), rs.getTime(1));
        assertEquals(LocalTime.of(10, 11, 12), rs.getObject(1, LocalTime.class));
        assertTrue(rs.next());
        assertEquals(Timestamp.valueOf("2024-01-15 23:30:00"), rs.getTimestamp(1));
      }
    } finally {
      TimeZone.setDefault(before);
    }
  }

  // ---- ResultSet values ----

  @Test
  void getObjectConvertsToTheRequestedType() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (i INTEGER, f FLOAT, ts TIMESTAMP, b BOOLEAN, s TEXT)");
      s.execute("INSERT INTO t VALUES (42, 2.5, '2024-03-01 12:00:00', true, 'x')");
      try (ResultSet rs = s.executeQuery("SELECT * FROM t")) {
        assertTrue(rs.next());
        assertEquals(42, rs.getObject(1, Integer.class));
        assertEquals(42L, rs.getObject(1, Long.class));
        assertEquals(42.0, rs.getObject(1, Double.class));
        assertEquals(new BigDecimal("42"), rs.getObject(1, BigDecimal.class));
        assertEquals("42", rs.getObject(1, String.class));
        assertEquals(2.5f, rs.getObject(2, Float.class));
        Instant t = Instant.parse("2024-03-01T12:00:00Z");
        assertInstanceOf(Timestamp.class, rs.getObject(3));
        assertEquals(Timestamp.from(t), rs.getObject(3, Timestamp.class));
        assertEquals(t, rs.getObject(3, Instant.class));
        assertEquals(LocalDateTime.of(2024, 3, 1, 12, 0), rs.getObject(3, LocalDateTime.class));
        assertEquals(t.atOffset(ZoneOffset.UTC), rs.getObject(3, OffsetDateTime.class));
        assertEquals(true, rs.getObject(4, Boolean.class));
        SQLException e = assertThrows(SQLException.class, () -> rs.getObject(5, Integer.class));
        assertEquals("22018", e.getSQLState());
      }
    }
  }

  @Test
  void gettersRefuseValuesTheyCannotRepresent() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      try (ResultSet rs = s.executeQuery("SELECT 'abc', 99999999999, '1', 'no', 'xyz'")) {
        assertTrue(rs.next());
        assertEquals("22018", assertThrows(SQLException.class, () -> rs.getLong(1)).getSQLState());
        assertEquals("22003", assertThrows(SQLException.class, () -> rs.getInt(2)).getSQLState());
        assertEquals(99999999999L, rs.getLong(2));
        assertTrue(rs.getBoolean(3));
        assertFalse(rs.getBoolean(4));
        assertThrows(SQLException.class, () -> rs.getBoolean(5));
        assertThrows(SQLException.class, () -> rs.getDate(1));
      }
    }
  }

  @Test
  void resultSetMetaDataReportsColumnTypes() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute(
          "CREATE TABLE t (i INTEGER, f FLOAT, s TEXT, b BOOLEAN, ts TIMESTAMP, j JSON, n TEXT)");
      s.execute("INSERT INTO t VALUES (1, 1.5, 'a', true, '2024-01-01', '{\"a\":1}', NULL)");
      try (ResultSet rs = s.executeQuery("SELECT * FROM t")) {
        ResultSetMetaData md = rs.getMetaData();
        int[] types = {
          Types.BIGINT,
          Types.DOUBLE,
          Types.VARCHAR,
          Types.BOOLEAN,
          Types.TIMESTAMP,
          Types.OTHER,
          Types.NULL
        };
        String[] names = {"INTEGER", "FLOAT", "TEXT", "BOOLEAN", "TIMESTAMP", "JSON", "NULL"};
        for (int i = 0; i < types.length; i++) {
          assertEquals(types[i], md.getColumnType(i + 1), "column " + (i + 1));
          assertEquals(names[i], md.getColumnTypeName(i + 1), "column " + (i + 1));
        }
        assertEquals(Long.class.getName(), md.getColumnClassName(1));
        assertEquals(Timestamp.class.getName(), md.getColumnClassName(5));
      }
    }
  }

  @Test
  void resultsStreamInFetchSizeChunksAndHonourMaxRows() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      try (PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?)")) {
        for (int i = 1; i <= 1000; i++) {
          ps.setInt(1, i);
          ps.addBatch();
        }
        ps.executeBatch();
      }
      s.setFetchSize(7);
      try (ResultSet rs = s.executeQuery("SELECT id FROM t ORDER BY id")) {
        assertTrue(rs.isBeforeFirst());
        int n = 0;
        while (rs.next()) {
          assertEquals(++n, rs.getInt(1));
          assertEquals(n, rs.getRow());
          assertEquals(n == 1000, rs.isLast());
        }
        assertEquals(1000, n);
        assertTrue(rs.isAfterLast());
      }
      s.setMaxRows(5);
      try (ResultSet rs = s.executeQuery("SELECT id FROM t ORDER BY id")) {
        int n = 0;
        while (rs.next()) n++;
        assertEquals(5, n);
      }
    }
  }

  @Test
  void columnLabelsResolveWithoutCase() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT 1 AS a, 2 AS b, 3 AS a")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt("A"));
      assertEquals(2, rs.getInt("b"));
      assertEquals(1, rs.findColumn("a")); // the first match wins
      assertEquals(
          "42703", assertThrows(SQLException.class, () -> rs.findColumn("z")).getSQLState());
    }
  }

  // ---- Generated keys, isolation, savepoints, SQLState ----

  @Test
  void generatedKeysComeFromTheInsert() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (id INTEGER PRIMARY KEY AUTO_INCREMENT, name TEXT)");
      assertEquals(
          1, s.executeUpdate("INSERT INTO t (name) VALUES ('a')", Statement.RETURN_GENERATED_KEYS));
      try (ResultSet keys = s.getGeneratedKeys()) {
        assertTrue(keys.next());
        assertEquals(1, keys.getLong(1));
        assertFalse(keys.next());
      }
      try (PreparedStatement ps =
          c.prepareStatement("INSERT INTO t (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
        ps.setString(1, "b");
        assertEquals(1, ps.executeUpdate());
        try (ResultSet keys = ps.getGeneratedKeys()) {
          assertTrue(keys.next());
          assertEquals(2, keys.getLong("id"));
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement("INSERT INTO t (name) VALUES (?)", new String[] {"id", "name"})) {
        ps.setString(1, "c");
        ps.executeUpdate();
        try (ResultSet keys = ps.getGeneratedKeys()) {
          assertTrue(keys.next());
          assertEquals(3, keys.getLong(1));
          assertEquals("c", keys.getString(2));
        }
      }
      assertEquals(1, s.executeLargeUpdate("DELETE FROM t WHERE id = 1"));
    }
  }

  @Test
  void repeatableReadRunsTransactionsOnASnapshot() throws Exception {
    Path dir = Files.createTempDirectory("stoolap-iso");
    String url = "jdbc:stoolap:file://" + dir.toAbsolutePath();
    try (Connection a = DriverManager.getConnection(url);
        Connection b = DriverManager.getConnection(url);
        Statement sa = a.createStatement();
        Statement sb = b.createStatement()) {
      sa.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      a.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      assertEquals(Connection.TRANSACTION_REPEATABLE_READ, a.getTransactionIsolation());
      a.setAutoCommit(false);
      assertEquals(0, count(a, "t"));
      sb.executeUpdate("INSERT INTO t VALUES (1)");
      assertEquals(0, count(a, "t")); // the snapshot does not see b's commit
      a.commit();
      assertEquals(1, count(a, "t"));
      assertThrows(
          SQLException.class, () -> a.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE));
    }
  }

  @Test
  void savepointsRollBackPartOfATransaction() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      c.setAutoCommit(false);
      s.executeUpdate("INSERT INTO t VALUES (1)");
      Savepoint sp = c.setSavepoint("before_two");
      s.executeUpdate("INSERT INTO t VALUES (2)");
      c.rollback(sp);
      Savepoint unnamed = c.setSavepoint();
      s.executeUpdate("INSERT INTO t VALUES (3)");
      c.releaseSavepoint(unnamed);
      c.commit();
      c.setAutoCommit(true);
      assertEquals(2, count(c, "t"));
    }
  }

  @Test
  void errorsCarryTheirSqlState() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
      s.execute("INSERT INTO t VALUES (1, 'a')");
      assertEquals(
          "23505",
          assertThrows(SQLException.class, () -> s.execute("INSERT INTO t VALUES (1, 'b')"))
              .getSQLState());
      assertEquals(
          "23502",
          assertThrows(SQLException.class, () -> s.execute("INSERT INTO t VALUES (2, NULL)"))
              .getSQLState());
      assertEquals(
          "42P01",
          assertThrows(SQLException.class, () -> s.executeQuery("SELECT * FROM nope"))
              .getSQLState());
      assertEquals(
          "42601", assertThrows(SQLException.class, () -> s.execute("SELEC 1")).getSQLState());
    }
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      db.execute("INSERT INTO t VALUES (1)");
      assertEquals(
          "23505",
          assertThrows(StoolapException.class, () -> db.execute("INSERT INTO t VALUES (1)"))
              .getSQLState());
    }
  }

  // ---- Lifecycle ----

  @Test
  void closingAConnectionClosesItsStatementsAndReleasesTheDatabase() throws Exception {
    Path dir = Files.createTempDirectory("stoolap-lock");
    String url = "jdbc:stoolap:file://" + dir.toAbsolutePath();
    Connection c = DriverManager.getConnection(url);
    c.createStatement().execute("CREATE TABLE t (id INTEGER)");
    PreparedStatement leaked = c.prepareStatement("SELECT * FROM t");
    ResultSet open = c.createStatement().executeQuery("SELECT * FROM t");
    c.close();
    assertTrue(leaked.isClosed());
    assertTrue(open.isClosed());
    try (Connection again = DriverManager.getConnection(url)) {
      assertEquals(0, count(again, "t"));
    }
  }

  // ---- DatabaseMetaData ----

  @Test
  void metaDataDescribesTablesColumnsKeysAndIndexes() throws Exception {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute(
          "CREATE TABLE users (id INTEGER PRIMARY KEY AUTO_INCREMENT, name TEXT NOT NULL,"
              + " email TEXT UNIQUE, score FLOAT DEFAULT 1.5)");
      s.execute(
          "CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER REFERENCES users(id)"
              + " ON DELETE CASCADE, amount FLOAT)");
      s.execute("CREATE INDEX idx_name ON users(name)");
      s.execute("CREATE VIEW big_orders AS SELECT id, amount FROM orders WHERE amount > 100");
      DatabaseMetaData md = c.getMetaData();

      List<String> tables = new ArrayList<>();
      try (ResultSet rs = md.getTables(null, null, "%", new String[] {"TABLE"})) {
        while (rs.next()) {
          assertEquals("TABLE", rs.getString("TABLE_TYPE"));
          tables.add(rs.getString("TABLE_NAME"));
        }
      }
      assertEquals(List.of("orders", "users"), tables);
      try (ResultSet rs = md.getTables(null, null, "US_RS", null)) {
        assertTrue(rs.next());
        assertEquals("users", rs.getString("TABLE_NAME"));
        assertFalse(rs.next());
      }
      try (ResultSet rs = md.getTables(null, null, "flyway_schema_history", null)) {
        assertFalse(rs.next());
      }
      try (ResultSet rs = md.getTables(null, null, "%", new String[] {"VIEW"})) {
        assertTrue(rs.next());
        assertEquals("big_orders", rs.getString("TABLE_NAME"));
      }

      try (ResultSet rs = md.getColumns(null, null, "users", null)) {
        assertTrue(rs.next());
        assertEquals("id", rs.getString("COLUMN_NAME"));
        assertEquals(Types.BIGINT, rs.getInt("DATA_TYPE"));
        assertEquals("YES", rs.getString("IS_AUTOINCREMENT"));
        assertEquals(1, rs.getInt("ORDINAL_POSITION"));
        assertTrue(rs.next());
        assertEquals("name", rs.getString("COLUMN_NAME"));
        assertEquals(Types.VARCHAR, rs.getInt("DATA_TYPE"));
        assertEquals(DatabaseMetaData.columnNoNulls, rs.getInt("NULLABLE"));
        assertTrue(rs.next());
        assertTrue(rs.next());
        assertEquals("score", rs.getString("COLUMN_NAME"));
        assertEquals("1.5", rs.getString("COLUMN_DEF"));
        assertFalse(rs.next());
      }
      try (ResultSet rs = md.getColumns(null, null, "big_orders", "amo%")) {
        assertTrue(rs.next());
        assertEquals("amount", rs.getString("COLUMN_NAME"));
        assertFalse(rs.next());
      }

      try (ResultSet rs = md.getPrimaryKeys(null, null, "users")) {
        assertTrue(rs.next());
        assertEquals("id", rs.getString("COLUMN_NAME"));
        assertEquals(1, rs.getInt("KEY_SEQ"));
      }

      List<String> indexes = new ArrayList<>();
      try (ResultSet rs = md.getIndexInfo(null, null, "users", false, false)) {
        while (rs.next()) {
          indexes.add(rs.getString("INDEX_NAME") + ":" + rs.getBoolean("NON_UNIQUE"));
        }
      }
      assertTrue(indexes.contains("PRIMARY:false"), indexes.toString());
      assertTrue(indexes.contains("idx_name:true"), indexes.toString());
      try (ResultSet rs = md.getIndexInfo(null, null, "users", true, false)) {
        while (rs.next()) assertFalse(rs.getBoolean("NON_UNIQUE"));
      }

      try (ResultSet rs = md.getImportedKeys(null, null, "orders")) {
        assertTrue(rs.next());
        assertEquals("users", rs.getString("PKTABLE_NAME"));
        assertEquals("id", rs.getString("PKCOLUMN_NAME"));
        assertEquals("user_id", rs.getString("FKCOLUMN_NAME"));
        assertEquals(DatabaseMetaData.importedKeyCascade, rs.getInt("DELETE_RULE"));
      }
      try (ResultSet rs = md.getExportedKeys(null, null, "users")) {
        assertTrue(rs.next());
        assertEquals("orders", rs.getString("FKTABLE_NAME"));
      }

      try (ResultSet rs = md.getTypeInfo()) {
        int n = 0;
        while (rs.next()) n++;
        assertTrue(n >= 5);
      }
      try (ResultSet rs = md.getSchemas()) {
        assertFalse(rs.next());
      }
      try (ResultSet rs = md.getCatalogs()) {
        assertFalse(rs.next());
      }
      try (ResultSet rs = md.getTableTypes()) {
        assertTrue(rs.next());
      }
      assertTrue(md.supportsGetGeneratedKeys());
      assertTrue(md.dataDefinitionCausesTransactionCommit());
    }
  }
}
