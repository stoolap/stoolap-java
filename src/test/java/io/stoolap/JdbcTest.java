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
import java.sql.*;
import org.junit.jupiter.api.Test;

/** Tests for the Stoolap JDBC driver. */
class JdbcTest {

  @Test
  void testDriverRegistration() throws Exception {
    Driver driver = DriverManager.getDriver("jdbc:stoolap:memory://");
    assertNotNull(driver);
    assertTrue(driver.acceptsURL("jdbc:stoolap:memory://"));
    assertFalse(driver.acceptsURL("jdbc:postgresql://localhost/test"));
  }

  @Test
  void testConnection() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      assertNotNull(conn);
      assertFalse(conn.isClosed());
      assertTrue(conn.isValid(1));
    }
  }

  @Test
  void testCreateTableAndQuery() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
      stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice', 30)");
      stmt.executeUpdate("INSERT INTO users VALUES (2, 'Bob', 25)");

      ResultSet rs = stmt.executeQuery("SELECT id, name, age FROM users ORDER BY id");
      assertTrue(rs.next());
      assertEquals(1, rs.getInt("id"));
      assertEquals("Alice", rs.getString("name"));
      assertEquals(30, rs.getInt("age"));

      assertTrue(rs.next());
      assertEquals(2, rs.getInt("id"));
      assertEquals("Bob", rs.getString("name"));
      assertEquals(25, rs.getInt("age"));

      assertFalse(rs.next());
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testPreparedStatement() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, val FLOAT)");
      stmt.close();

      PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES ($1, $2)");
      ps.setLong(1, 1);
      ps.setDouble(2, 3.14);
      ps.executeUpdate();
      ps.setLong(1, 2);
      ps.setDouble(2, 2.71);
      ps.executeUpdate();
      ps.close();

      stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery("SELECT id, val FROM t ORDER BY id");
      assertTrue(rs.next());
      assertEquals(1, rs.getLong(1));
      assertEquals(3.14, rs.getDouble(2), 0.01);
      assertTrue(rs.next());
      assertEquals(2, rs.getLong(1));
      assertEquals(2.71, rs.getDouble(2), 0.01);
      assertFalse(rs.next());
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testTransaction() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, val INTEGER)");
      stmt.close();

      conn.setAutoCommit(false);
      stmt = conn.createStatement();
      stmt.executeUpdate("INSERT INTO t VALUES (1, 100)");
      stmt.executeUpdate("INSERT INTO t VALUES (2, 200)");
      conn.commit();

      ResultSet rs = stmt.executeQuery("SELECT SUM(val) FROM t");
      assertTrue(rs.next());
      assertEquals(300, rs.getLong(1));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testTransactionRollback() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER)");
      stmt.executeUpdate("INSERT INTO t VALUES (1)");
      stmt.close();

      conn.setAutoCommit(false);
      stmt = conn.createStatement();
      stmt.executeUpdate("INSERT INTO t VALUES (2)");
      conn.rollback();

      ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t");
      assertTrue(rs.next());
      assertEquals(1, rs.getLong(1));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testBatchInsert() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      stmt.close();

      PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES ($1, $2)");
      for (int i = 0; i < 50; i++) {
        ps.setLong(1, i);
        ps.setString(2, "name_" + i);
        ps.addBatch();
      }
      int[] results = ps.executeBatch();
      assertEquals(50, results.length);
      ps.close();

      stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t");
      assertTrue(rs.next());
      assertEquals(50, rs.getLong(1));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testNullHandling() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      stmt.executeUpdate("INSERT INTO t VALUES (1, NULL)");

      ResultSet rs = stmt.executeQuery("SELECT id, name FROM t");
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertNull(rs.getString(2));
      assertTrue(rs.wasNull());
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testResultSetMetaData() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, name TEXT, val FLOAT)");
      stmt.executeUpdate("INSERT INTO t VALUES (1, 'test', 1.5)");

      ResultSet rs = stmt.executeQuery("SELECT id, name, val FROM t");
      ResultSetMetaData meta = rs.getMetaData();
      assertEquals(3, meta.getColumnCount());
      assertEquals("id", meta.getColumnName(1));
      assertEquals("name", meta.getColumnName(2));
      assertEquals("val", meta.getColumnName(3));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testDatabaseMetaData() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      DatabaseMetaData meta = conn.getMetaData();
      assertEquals("Stoolap", meta.getDatabaseProductName());
      assertEquals("Stoolap JDBC Driver", meta.getDriverName());
      assertTrue(meta.supportsTransactions());
      assertTrue(meta.supportsGroupBy());
      assertTrue(meta.supportsUnionAll());
    }
  }

  @Test
  void testMultipleDataTypes() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (i INTEGER, f FLOAT, t TEXT, b BOOLEAN)");
      stmt.executeUpdate("INSERT INTO t VALUES (42, 3.14, 'hello', true)");

      ResultSet rs = stmt.executeQuery("SELECT * FROM t");
      assertTrue(rs.next());
      assertEquals(42, rs.getInt(1));
      assertEquals(3.14, rs.getDouble(2), 0.01);
      assertEquals("hello", rs.getString(3));
      assertTrue(rs.getBoolean(4));
      assertFalse(rs.next());
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testLargeResultSet() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, name TEXT)");

      PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES ($1, $2)");
      for (int i = 0; i < 10000; i++) {
        ps.setLong(1, i);
        ps.setString(2, "name_" + i);
        ps.addBatch();
      }
      ps.executeBatch();
      ps.close();

      ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t");
      assertTrue(rs.next());
      assertEquals(10000, rs.getLong(1));
      rs.close();

      // Verify bulk fetch works for large result set
      rs = stmt.executeQuery("SELECT id, name FROM t ORDER BY id LIMIT 100");
      int count = 0;
      while (rs.next()) {
        assertEquals(count, rs.getInt(1));
        assertEquals("name_" + count, rs.getString(2));
        count++;
      }
      assertEquals(100, count);
      rs.close();
      stmt.close();
    }
  }

  // --- Type conversion tests ---

  @Test
  void testGetLongFromInt() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (val INTEGER)");
      stmt.executeUpdate("INSERT INTO t VALUES (9999999999)");

      ResultSet rs = stmt.executeQuery("SELECT val FROM t");
      assertTrue(rs.next());
      assertEquals(9999999999L, rs.getLong(1));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testGetBigDecimal() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (i INTEGER, f FLOAT)");
      stmt.executeUpdate("INSERT INTO t VALUES (42, 3.14)");

      ResultSet rs = stmt.executeQuery("SELECT i, f FROM t");
      assertTrue(rs.next());
      assertEquals(BigDecimal.valueOf(42), rs.getBigDecimal(1));
      assertEquals(3.14, rs.getBigDecimal(2).doubleValue(), 0.01);
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testGetByte() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (val INTEGER)");
      stmt.executeUpdate("INSERT INTO t VALUES (42)");

      ResultSet rs = stmt.executeQuery("SELECT val FROM t");
      assertTrue(rs.next());
      assertEquals((byte) 42, rs.getByte(1));
      assertEquals((short) 42, rs.getShort(1));
      assertEquals(42, rs.getInt(1));
      assertEquals(42.0f, rs.getFloat(1));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testGetStringFromNumeric() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (i INTEGER, f FLOAT, b BOOLEAN)");
      stmt.executeUpdate("INSERT INTO t VALUES (42, 3.14, true)");

      ResultSet rs = stmt.executeQuery("SELECT i, f, b FROM t");
      assertTrue(rs.next());
      assertEquals("42", rs.getString(1));
      assertNotNull(rs.getString(2)); // "3.14"
      assertEquals("true", rs.getString(3));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testNullReturnsDefaults() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (i INTEGER, f FLOAT, b BOOLEAN, s TEXT)");
      stmt.executeUpdate("INSERT INTO t VALUES (NULL, NULL, NULL, NULL)");

      ResultSet rs = stmt.executeQuery("SELECT * FROM t");
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      assertTrue(rs.wasNull());
      assertEquals(0.0, rs.getDouble(2));
      assertTrue(rs.wasNull());
      assertFalse(rs.getBoolean(3));
      assertTrue(rs.wasNull());
      assertNull(rs.getString(4));
      assertTrue(rs.wasNull());
      rs.close();
      stmt.close();
    }
  }

  // --- PreparedStatement type setter tests ---

  @Test
  void testPreparedStatementAllTypes() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (i INTEGER, f FLOAT, s TEXT, b BOOLEAN)");
      stmt.close();

      PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES ($1, $2, $3, $4)");
      ps.setInt(1, 100);
      ps.setFloat(2, 2.5f);
      ps.setString(3, "test");
      ps.setBoolean(4, true);
      ps.executeUpdate();

      ps.setShort(1, (short) 200);
      ps.setDouble(2, 5.5);
      ps.setString(3, "test2");
      ps.setBoolean(4, false);
      ps.executeUpdate();
      ps.close();

      stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery("SELECT * FROM t ORDER BY i");
      assertTrue(rs.next());
      assertEquals(100, rs.getInt(1));
      assertEquals(2.5, rs.getFloat(2), 0.01);
      assertEquals("test", rs.getString(3));
      assertTrue(rs.getBoolean(4));

      assertTrue(rs.next());
      assertEquals(200, rs.getInt(1));
      assertEquals(5.5, rs.getDouble(2), 0.01);
      assertEquals("test2", rs.getString(3));
      assertFalse(rs.getBoolean(4));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testPreparedStatementSetNull() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      stmt.close();

      PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES ($1, $2)");
      ps.setLong(1, 1);
      ps.setNull(2, Types.VARCHAR);
      ps.executeUpdate();
      ps.close();

      stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery("SELECT name FROM t");
      assertTrue(rs.next());
      assertNull(rs.getString(1));
      assertTrue(rs.wasNull());
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testPreparedStatementSetObject() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      stmt.close();

      PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES ($1, $2)");
      ps.setObject(1, 42L);
      ps.setObject(2, "hello");
      ps.executeUpdate();
      ps.close();

      stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery("SELECT * FROM t");
      assertTrue(rs.next());
      assertEquals(42, rs.getInt(1));
      assertEquals("hello", rs.getString(2));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testClearParameters() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      stmt.close();

      PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES ($1, $2)");
      ps.setLong(1, 1);
      ps.setString(2, "first");
      ps.clearParameters();
      ps.setLong(1, 99);
      ps.setString(2, "cleared");
      ps.executeUpdate();
      ps.close();

      stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery("SELECT id, name FROM t");
      assertTrue(rs.next());
      assertEquals(99, rs.getInt(1));
      assertEquals("cleared", rs.getString(2));
      rs.close();
      stmt.close();
    }
  }

  // --- ResultSet navigation tests ---

  @Test
  void testResultSetFindColumn() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, user_name TEXT, score FLOAT)");
      stmt.executeUpdate("INSERT INTO t VALUES (1, 'test', 9.5)");

      ResultSet rs = stmt.executeQuery("SELECT id, user_name, score FROM t");
      assertEquals(1, rs.findColumn("id"));
      assertEquals(2, rs.findColumn("user_name"));
      assertEquals(3, rs.findColumn("score"));
      // Case insensitive
      assertEquals(1, rs.findColumn("ID"));
      assertEquals(2, rs.findColumn("USER_NAME"));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testResultSetFindColumnNotFound() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER)");
      stmt.executeUpdate("INSERT INTO t VALUES (1)");

      ResultSet rs = stmt.executeQuery("SELECT id FROM t");
      assertThrows(SQLException.class, () -> rs.findColumn("nonexistent"));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testResultSetPositionInfo() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER)");
      stmt.executeUpdate("INSERT INTO t VALUES (1)");
      stmt.executeUpdate("INSERT INTO t VALUES (2)");

      ResultSet rs = stmt.executeQuery("SELECT id FROM t ORDER BY id");
      assertTrue(rs.isBeforeFirst());
      assertEquals(0, rs.getRow());

      assertTrue(rs.next());
      assertTrue(rs.isFirst());
      assertEquals(1, rs.getRow());

      assertTrue(rs.next());
      assertTrue(rs.isLast());
      assertEquals(2, rs.getRow());

      assertFalse(rs.next());
      assertTrue(rs.isAfterLast());
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testResultSetGetByColumnName() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, name TEXT, score FLOAT, active BOOLEAN)");
      stmt.executeUpdate("INSERT INTO t VALUES (1, 'Alice', 95.5, true)");

      ResultSet rs = stmt.executeQuery("SELECT id, name, score, active FROM t");
      assertTrue(rs.next());
      assertEquals(1, rs.getInt("id"));
      assertEquals("Alice", rs.getString("name"));
      assertEquals(95.5, rs.getDouble("score"), 0.01);
      assertTrue(rs.getBoolean("active"));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testResultSetGetObject() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (i INTEGER, f FLOAT, s TEXT, b BOOLEAN)");
      stmt.executeUpdate("INSERT INTO t VALUES (42, 3.14, 'hello', true)");

      ResultSet rs = stmt.executeQuery("SELECT * FROM t");
      assertTrue(rs.next());
      assertEquals(42L, rs.getObject(1));
      assertEquals(3.14, (Double) rs.getObject(2), 0.01);
      assertEquals("hello", rs.getObject(3));
      assertEquals(true, rs.getObject(4));
      rs.close();
      stmt.close();
    }
  }

  // --- Statement.execute() return value tests ---

  @Test
  void testExecuteReturnsCorrectly() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();

      // DDL returns false (no result set)
      assertFalse(stmt.execute("CREATE TABLE t (id INTEGER)"));

      // DML returns false
      assertFalse(stmt.execute("INSERT INTO t VALUES (1)"));

      // SELECT returns true (has result set)
      assertTrue(stmt.execute("SELECT * FROM t"));
      ResultSet rs = stmt.getResultSet();
      assertNotNull(rs);
      assertTrue(rs.next());
      rs.close();

      // WITH (CTE) returns true
      assertTrue(stmt.execute("WITH cte AS (SELECT 1 AS x) SELECT * FROM cte"));
      rs = stmt.getResultSet();
      assertNotNull(rs);
      rs.close();

      stmt.close();
    }
  }

  @Test
  void testGetUpdateCount() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER)");

      int affected = stmt.executeUpdate("INSERT INTO t VALUES (1)");
      assertEquals(1, affected);
      assertEquals(1, stmt.getUpdateCount());

      stmt.close();
    }
  }

  // --- Connection behavior tests ---

  @Test
  void testAutoCommitDefault() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      assertTrue(conn.getAutoCommit());
    }
  }

  @Test
  void testSwitchAutoCommit() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER)");

      // Auto-commit mode: each statement auto-commits
      stmt.executeUpdate("INSERT INTO t VALUES (1)");

      // Switch to manual
      conn.setAutoCommit(false);
      assertFalse(conn.getAutoCommit());
      stmt.executeUpdate("INSERT INTO t VALUES (2)");
      conn.commit();

      // Switch back to auto (should commit pending)
      stmt.executeUpdate("INSERT INTO t VALUES (3)");
      conn.setAutoCommit(true);
      assertTrue(conn.getAutoCommit());

      ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t");
      assertTrue(rs.next());
      assertEquals(3, rs.getLong(1));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testCommitInAutoCommitThrows() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      assertTrue(conn.getAutoCommit());
      assertThrows(SQLException.class, conn::commit);
      assertThrows(SQLException.class, conn::rollback);
    }
  }

  @Test
  void testConnectionClosedThrows() throws Exception {
    Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://");
    conn.close();
    assertTrue(conn.isClosed());
    assertThrows(SQLException.class, conn::createStatement);
    assertThrows(SQLException.class, () -> conn.prepareStatement("SELECT 1"));
  }

  // --- Closed resource tests ---

  @Test
  void testClosedStatementThrows() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.close();
      assertTrue(stmt.isClosed());
      assertThrows(SQLException.class, () -> stmt.executeQuery("SELECT 1"));
      assertThrows(SQLException.class, () -> stmt.executeUpdate("SELECT 1"));
    }
  }

  @Test
  void testClosedResultSetThrows() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER)");
      stmt.executeUpdate("INSERT INTO t VALUES (1)");

      ResultSet rs = stmt.executeQuery("SELECT * FROM t");
      rs.close();
      assertTrue(rs.isClosed());
      assertThrows(SQLException.class, rs::next);
      stmt.close();
    }
  }

  // --- JDBC join and complex query tests ---

  @Test
  void testJdbcJoinQuery() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE authors (id INTEGER PRIMARY KEY, name TEXT)");
      stmt.execute("CREATE TABLE books (id INTEGER, author_id INTEGER, title TEXT)");
      stmt.executeUpdate("INSERT INTO authors VALUES (1, 'Tolkien')");
      stmt.executeUpdate("INSERT INTO authors VALUES (2, 'Asimov')");
      stmt.executeUpdate("INSERT INTO books VALUES (1, 1, 'The Hobbit')");
      stmt.executeUpdate("INSERT INTO books VALUES (2, 1, 'LOTR')");
      stmt.executeUpdate("INSERT INTO books VALUES (3, 2, 'Foundation')");

      ResultSet rs =
          stmt.executeQuery(
              "SELECT a.name, COUNT(b.id) as book_count "
                  + "FROM authors a LEFT JOIN books b ON a.id = b.author_id "
                  + "GROUP BY a.name ORDER BY book_count DESC");
      assertTrue(rs.next());
      assertEquals("Tolkien", rs.getString(1));
      assertEquals(2, rs.getInt(2));
      assertTrue(rs.next());
      assertEquals("Asimov", rs.getString(1));
      assertEquals(1, rs.getInt(2));
      assertFalse(rs.next());
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testJdbcSubquery() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, val INTEGER)");
      stmt.executeUpdate("INSERT INTO t VALUES (1, 10)");
      stmt.executeUpdate("INSERT INTO t VALUES (2, 20)");
      stmt.executeUpdate("INSERT INTO t VALUES (3, 30)");

      ResultSet rs =
          stmt.executeQuery("SELECT id, val FROM t WHERE val = (SELECT MAX(val) FROM t)");
      assertTrue(rs.next());
      assertEquals(3, rs.getInt(1));
      assertEquals(30, rs.getInt(2));
      assertFalse(rs.next());
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testJdbcAggregateQuery() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE sales (product TEXT, region TEXT, amount FLOAT)");
      stmt.executeUpdate("INSERT INTO sales VALUES ('Widget', 'East', 100)");
      stmt.executeUpdate("INSERT INTO sales VALUES ('Widget', 'West', 150)");
      stmt.executeUpdate("INSERT INTO sales VALUES ('Gadget', 'East', 200)");
      stmt.executeUpdate("INSERT INTO sales VALUES ('Gadget', 'West', 250)");

      ResultSet rs =
          stmt.executeQuery(
              "SELECT product, SUM(amount) as total, AVG(amount) as avg_amount "
                  + "FROM sales GROUP BY product ORDER BY total DESC");
      assertTrue(rs.next());
      assertEquals("Gadget", rs.getString("product"));
      assertEquals(450.0, rs.getDouble("total"), 0.01);
      assertEquals(225.0, rs.getDouble("avg_amount"), 0.01);
      assertTrue(rs.next());
      assertEquals("Widget", rs.getString("product"));
      assertEquals(250.0, rs.getDouble("total"), 0.01);
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testJdbcTimestamp() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE events (id INTEGER, ts TIMESTAMP)");
      stmt.executeUpdate("INSERT INTO events VALUES (1, '2025-06-15 14:30:00')");

      ResultSet rs = stmt.executeQuery("SELECT ts FROM events");
      assertTrue(rs.next());
      Timestamp ts = rs.getTimestamp(1);
      assertNotNull(ts);
      assertFalse(rs.wasNull());
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testJdbcTimestampNull() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE events (id INTEGER, ts TIMESTAMP)");
      stmt.executeUpdate("INSERT INTO events VALUES (1, NULL)");

      ResultSet rs = stmt.executeQuery("SELECT ts FROM events");
      assertTrue(rs.next());
      assertNull(rs.getTimestamp(1));
      assertTrue(rs.wasNull());
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testJdbcEmptyResultSet() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER)");

      ResultSet rs = stmt.executeQuery("SELECT * FROM t");
      assertFalse(rs.next());
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testJdbcMultipleResultSets() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER)");
      stmt.executeUpdate("INSERT INTO t VALUES (1)");
      stmt.executeUpdate("INSERT INTO t VALUES (2)");

      // First query
      ResultSet rs1 = stmt.executeQuery("SELECT * FROM t WHERE id = 1");
      assertTrue(rs1.next());
      assertEquals(1, rs1.getInt(1));
      rs1.close();

      // Second query on same statement
      ResultSet rs2 = stmt.executeQuery("SELECT * FROM t WHERE id = 2");
      assertTrue(rs2.next());
      assertEquals(2, rs2.getInt(1));
      rs2.close();

      stmt.close();
    }
  }

  @Test
  void testPreparedStatementReuse() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      stmt.close();

      PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES ($1, $2)");
      for (int i = 0; i < 100; i++) {
        ps.setLong(1, i);
        ps.setString(2, "item_" + i);
        ps.executeUpdate();
      }
      ps.close();

      stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t");
      assertTrue(rs.next());
      assertEquals(100, rs.getInt(1));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testResultSetMetaDataColumnLabel() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      stmt.executeUpdate("INSERT INTO t VALUES (1, 'test')");

      ResultSet rs = stmt.executeQuery("SELECT id AS item_id, name AS item_name FROM t");
      ResultSetMetaData meta = rs.getMetaData();
      assertEquals(2, meta.getColumnCount());
      assertEquals("item_id", meta.getColumnLabel(1));
      assertEquals("item_name", meta.getColumnLabel(2));
      rs.close();
      stmt.close();
    }
  }

  @Test
  void testDatabaseMetaDataCapabilities() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      DatabaseMetaData meta = conn.getMetaData();
      assertTrue(meta.supportsOuterJoins());
      assertTrue(meta.supportsFullOuterJoins());
      assertTrue(meta.supportsSubqueriesInExists());
      assertTrue(meta.supportsSubqueriesInIns());
      assertTrue(meta.supportsCorrelatedSubqueries());
      assertTrue(meta.supportsUnion());
      assertTrue(meta.supportsColumnAliasing());
      assertTrue(meta.supportsExpressionsInOrderBy());
      assertFalse(meta.supportsStoredProcedures());
      assertFalse(meta.supportsSavepoints());
      assertEquals("\"", meta.getIdentifierQuoteString());
    }
  }

  @Test
  void testConnectionIsValid() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      assertTrue(conn.isValid(0));
      assertTrue(conn.isValid(5));
      assertFalse(conn.isClosed());
    }
  }

  @Test
  void testUnsupportedOperations() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      assertThrows(SQLFeatureNotSupportedException.class, () -> conn.prepareCall("SELECT 1"));
      assertThrows(SQLFeatureNotSupportedException.class, conn::setSavepoint);
    }
  }

  @Test
  void testUnwrap() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      assertTrue(conn.isWrapperFor(Connection.class));
      assertNotNull(conn.unwrap(Connection.class));
      assertFalse(conn.isWrapperFor(String.class));
      assertThrows(SQLException.class, () -> conn.unwrap(String.class));
    }
  }
}
