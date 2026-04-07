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

import io.stoolap.internal.BulkDecoder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tests for the core Stoolap Java API. */
class StoolapDBTest {

  @Test
  void testVersion() {
    String ver = StoolapDB.version();
    assertNotNull(ver);
    assertFalse(ver.isEmpty());
  }

  @Test
  void testOpenInMemory() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      assertNotNull(db);
      assertFalse(db.isClosed());
    }
  }

  @Test
  void testCreateTableAndInsert() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, active BOOLEAN)");
      long affected = db.execute("INSERT INTO users VALUES (1, 'Alice', true)");
      assertEquals(1, affected);
      affected = db.execute("INSERT INTO users VALUES (2, 'Bob', false)");
      assertEquals(1, affected);
    }
  }

  @Test
  void testQueryRowByRow() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, val FLOAT)");
      db.execute("INSERT INTO t VALUES (1, 3.14)");
      db.execute("INSERT INTO t VALUES (2, 2.71)");

      BulkDecoder.Result result = db.query("SELECT id, val FROM t ORDER BY id");
      assertEquals(2, result.getRowCount());

      assertEquals(1L, result.rows().get(0)[0]);
      assertEquals(3.14, (Double) result.rows().get(0)[1], 0.001);

      assertEquals(2L, result.rows().get(1)[0]);
      assertEquals(2.71, (Double) result.rows().get(1)[1], 0.001);
    }
  }

  @Test
  void testQueryBulkFetch() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      for (int i = 0; i < 100; i++) {
        db.execute("INSERT INTO t VALUES (" + i + ", 'name_" + i + "')");
      }

      BulkDecoder.Result result = db.query("SELECT id, name FROM t ORDER BY id");
      assertEquals(2, result.getColumnCount());
      assertEquals(100, result.getRowCount());
      assertEquals("id", result.columnNames()[0]);
      assertEquals("name", result.columnNames()[1]);

      // Check first and last rows
      assertEquals(0L, result.rows().get(0)[0]);
      assertEquals("name_0", result.rows().get(0)[1]);
      assertEquals(99L, result.rows().get(99)[0]);
      assertEquals("name_99", result.rows().get(99)[1]);
    }
  }

  @Test
  void testParameterizedQuery() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES ($1, $2)", 1L, "Alice");
      db.execute("INSERT INTO t VALUES ($1, $2)", 2L, "Bob");

      BulkDecoder.Result result = db.query("SELECT name FROM t WHERE id = $1", 1L);
      assertEquals(1, result.getRowCount());
      assertEquals("Alice", result.rows().get(0)[0]);
    }
  }

  @Test
  void testPreparedStatement() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");

      try (StoolapStmt stmt = db.prepare("INSERT INTO t VALUES ($1, $2)")) {
        stmt.execute(1L, "Alice");
        stmt.execute(2L, "Bob");
        stmt.execute(3L, "Charlie");
      }

      BulkDecoder.Result result = db.query("SELECT COUNT(*) FROM t");
      assertEquals(1, result.getRowCount());
      assertEquals(3L, result.rows().get(0)[0]);
    }
  }

  @Test
  void testTransaction() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, val INTEGER)");

      try (StoolapTx tx = db.begin()) {
        tx.execute("INSERT INTO t VALUES (1, 100)");
        tx.execute("INSERT INTO t VALUES (2, 200)");
        tx.commit();
      }

      BulkDecoder.Result result = db.query("SELECT SUM(val) FROM t");
      assertEquals(1, result.getRowCount());
      assertEquals(300L, result.rows().get(0)[0]);
    }
  }

  @Test
  void testTransactionRollback() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER)");
      db.execute("INSERT INTO t VALUES (1)");

      try (StoolapTx tx = db.begin()) {
        tx.execute("INSERT INTO t VALUES (2)");
        tx.rollback();
      }

      BulkDecoder.Result result = db.query("SELECT COUNT(*) FROM t");
      assertEquals(1, result.getRowCount());
      assertEquals(1L, result.rows().get(0)[0]);
    }
  }

  @Test
  void testTransactionAutoRollbackOnClose() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER)");
      db.execute("INSERT INTO t VALUES (1)");

      // Transaction not committed or rolled back, should auto-rollback
      try (StoolapTx tx = db.begin()) {
        tx.execute("INSERT INTO t VALUES (2)");
      }

      BulkDecoder.Result result = db.query("SELECT COUNT(*) FROM t");
      assertEquals(1, result.getRowCount());
      assertEquals(1L, result.rows().get(0)[0]);
    }
  }

  @Test
  void testNullValues() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, NULL)");

      BulkDecoder.Result result = db.query("SELECT id, name FROM t");
      assertEquals(1, result.getRowCount());
      assertEquals(1L, result.rows().get(0)[0]);
      assertNull(result.rows().get(0)[1]);
    }
  }

  @Test
  void testTimestamp() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, ts TIMESTAMP)");
      db.execute("INSERT INTO t VALUES (1, '2025-01-15 10:30:00')");

      BulkDecoder.Result result = db.query("SELECT ts FROM t");
      assertEquals(1, result.getRowCount());
      Instant ts = (Instant) result.rows().get(0)[0];
      assertNotNull(ts);
      assertEquals(2025, ts.atZone(ZoneOffset.UTC).getYear());
    }
  }

  @Test
  void testClone() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER)");
      db.execute("INSERT INTO t VALUES (1)");

      try (StoolapDB clone = db.cloneHandle()) {
        BulkDecoder.Result result = clone.query("SELECT COUNT(*) FROM t");
        assertEquals(1, result.getRowCount());
        assertEquals(1L, result.rows().get(0)[0]);
      }
    }
  }

  @Test
  void testMultipleTypes() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (i INTEGER, f FLOAT, t TEXT, b BOOLEAN)");
      db.execute("INSERT INTO t VALUES (42, 3.14, 'hello', true)");

      BulkDecoder.Result result = db.query("SELECT * FROM t");
      assertEquals(1, result.getRowCount());
      Object[] row = result.rows().get(0);
      assertEquals(42L, row[0]);
      assertEquals(3.14, (Double) row[1], 0.001);
      assertEquals("hello", row[2]);
      assertEquals(true, row[3]);
    }
  }

  @Test
  void testPreparedStatementInTransaction() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");

      try (StoolapStmt stmt = db.prepare("INSERT INTO t VALUES ($1, $2)")) {
        try (StoolapTx tx = db.begin()) {
          tx.execute(stmt, 1L, "Alice");
          tx.execute(stmt, 2L, "Bob");
          tx.commit();
        }
      }

      BulkDecoder.Result result = db.query("SELECT COUNT(*) FROM t");
      assertEquals(1, result.getRowCount());
      assertEquals(2L, result.rows().get(0)[0]);
    }
  }

  // --- Error handling tests ---

  @Test
  void testInvalidSqlThrows() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      assertThrows(StoolapException.class, () -> db.execute("NOT VALID SQL"));
    }
  }

  @Test
  void testQueryNonExistentTable() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      assertThrows(StoolapException.class, () -> db.query("SELECT * FROM no_such_table"));
    }
  }

  @Test
  void testDuplicatePrimaryKey() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'first')");
      assertThrows(
          StoolapException.class, () -> db.execute("INSERT INTO t VALUES (1, 'duplicate')"));
    }
  }

  @Test
  void testClosedDbThrows() throws Exception {
    StoolapDB db = StoolapDB.openInMemory();
    db.close();
    assertTrue(db.isClosed());
    assertThrows(StoolapException.class, () -> db.execute("SELECT 1"));
    assertThrows(StoolapException.class, () -> db.query("SELECT 1"));
    assertThrows(StoolapException.class, () -> db.prepare("SELECT 1"));
    assertThrows(StoolapException.class, () -> db.begin());
  }

  // --- Column metadata tests ---

  @Test
  void testColumnNames() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (user_id INTEGER, user_name TEXT, score FLOAT)");
      db.execute("INSERT INTO t VALUES (1, 'test', 9.5)");

      BulkDecoder.Result result = db.query("SELECT user_id, user_name, score FROM t");
      assertEquals(3, result.getColumnCount());
      assertEquals("user_id", result.columnNames()[0]);
      assertEquals("user_name", result.columnNames()[1]);
      assertEquals("score", result.columnNames()[2]);

      assertArrayEquals(new String[] {"user_id", "user_name", "score"}, result.columnNames());
    }
  }

  @Test
  void testColumnTypesViaValues() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (i INTEGER, f FLOAT, s TEXT, b BOOLEAN, ts TIMESTAMP)");
      db.execute("INSERT INTO t VALUES (1, 1.5, 'hello', true, '2025-06-15 12:00:00')");

      BulkDecoder.Result result = db.query("SELECT i, f, s, b, ts FROM t");
      assertEquals(1, result.getRowCount());
      Object[] row = result.rows().get(0);
      assertInstanceOf(Long.class, row[0]);
      assertInstanceOf(Double.class, row[1]);
      assertInstanceOf(String.class, row[2]);
      assertInstanceOf(Boolean.class, row[3]);
      assertInstanceOf(Instant.class, row[4]);
    }
  }

  // --- Parameterized query edge cases ---

  @Test
  void testParameterTypes() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (i INTEGER, f FLOAT, s TEXT, b BOOLEAN)");
      db.execute("INSERT INTO t VALUES ($1, $2, $3, $4)", 42L, 3.14, "test", true);

      BulkDecoder.Result result = db.query("SELECT * FROM t");
      assertEquals(1, result.getRowCount());
      Object[] row = result.rows().get(0);
      assertEquals(42L, row[0]);
      assertEquals(3.14, (Double) row[1], 0.001);
      assertEquals("test", row[2]);
      assertEquals(true, row[3]);
    }
  }

  @Test
  void testIntegerParameterSubtypes() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (a INTEGER, b INTEGER, c INTEGER, d INTEGER)");
      // Test that Integer, Short, Byte all map to INTEGER
      db.execute(
          "INSERT INTO t VALUES ($1, $2, $3, $4)",
          100L,
          Integer.valueOf(200),
          Short.valueOf((short) 300),
          Byte.valueOf((byte) 40));

      BulkDecoder.Result result = db.query("SELECT * FROM t");
      assertEquals(1, result.getRowCount());
      Object[] row = result.rows().get(0);
      assertEquals(100L, row[0]);
      assertEquals(200L, row[1]);
      assertEquals(300L, row[2]);
      assertEquals(40L, row[3]);
    }
  }

  @Test
  void testNullParameter() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES ($1, $2)", 1L, null);

      BulkDecoder.Result result = db.query("SELECT name FROM t WHERE id = 1");
      assertEquals(1, result.getRowCount());
      assertNull(result.rows().get(0)[0]);
    }
  }

  @Test
  void testTimestampParameter() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, ts TIMESTAMP)");
      Instant now = Instant.parse("2025-03-15T10:30:00Z");
      db.execute("INSERT INTO t VALUES ($1, $2)", 1L, now);

      BulkDecoder.Result result = db.query("SELECT ts FROM t WHERE id = 1");
      assertEquals(1, result.getRowCount());
      Instant ts = (Instant) result.rows().get(0)[0];
      assertEquals(now.getEpochSecond(), ts.getEpochSecond());
    }
  }

  // --- Prepared statement query tests ---

  @Test
  void testPreparedStatementQuery() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'Alice')");
      db.execute("INSERT INTO t VALUES (2, 'Bob')");
      db.execute("INSERT INTO t VALUES (3, 'Charlie')");

      try (StoolapStmt stmt = db.prepare("SELECT name FROM t WHERE id = $1")) {
        BulkDecoder.Result result = stmt.query(2L);
        assertEquals(1, result.getRowCount());
        assertEquals("Bob", result.rows().get(0)[0]);

        // Reuse same prepared statement
        BulkDecoder.Result result2 = stmt.query(3L);
        assertEquals(1, result2.getRowCount());
        assertEquals("Charlie", result2.rows().get(0)[0]);
      }
    }
  }

  @Test
  void testPreparedStatementGetSql() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      String sql = "SELECT $1 + $2";
      try (StoolapStmt stmt = db.prepare(sql)) {
        assertEquals(sql, stmt.getSql());
      }
    }
  }

  // --- Transaction query tests ---

  @Test
  void testTransactionQuery() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'outside')");

      try (StoolapTx tx = db.begin()) {
        tx.execute("INSERT INTO t VALUES (2, 'inside')");

        BulkDecoder.Result result = tx.query("SELECT COUNT(*) FROM t");
        assertEquals(1, result.getRowCount());
        assertEquals(2L, result.rows().get(0)[0]);
        tx.commit();
      }
    }
  }

  @Test
  void testTransactionQueryWithParams() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");

      try (StoolapTx tx = db.begin()) {
        tx.execute("INSERT INTO t VALUES ($1, $2)", 1L, "Alpha");
        tx.execute("INSERT INTO t VALUES ($1, $2)", 2L, "Beta");

        BulkDecoder.Result result = tx.query("SELECT name FROM t WHERE id = $1", 2L);
        assertEquals(1, result.getRowCount());
        assertEquals("Beta", result.rows().get(0)[0]);
        tx.commit();
      }
    }
  }

  @Test
  void testTransactionPreparedQuery() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, val INTEGER)");

      try (StoolapStmt stmt = db.prepare("SELECT SUM(val) FROM t")) {
        try (StoolapTx tx = db.begin()) {
          tx.execute("INSERT INTO t VALUES (1, 10)");
          tx.execute("INSERT INTO t VALUES (2, 20)");

          BulkDecoder.Result result = tx.query(stmt);
          assertEquals(1, result.getRowCount());
          assertEquals(30L, result.rows().get(0)[0]);
          tx.commit();
        }
      }
    }
  }

  @Test
  void testSnapshotIsolation() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, val INTEGER)");
      db.execute("INSERT INTO t VALUES (1, 100)");

      try (StoolapTx tx = db.begin(true)) { // snapshot
        // Insert outside the transaction
        db.execute("INSERT INTO t VALUES (2, 200)");

        // Snapshot should NOT see the new row
        BulkDecoder.Result result = tx.query("SELECT COUNT(*) FROM t");
        assertEquals(1, result.getRowCount());
        assertEquals(1L, result.rows().get(0)[0]);
        tx.commit();
      }
    }
  }

  // --- Bulk fetch edge cases ---

  @Test
  void testBulkFetchEmptyResult() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER)");

      BulkDecoder.Result result = db.query("SELECT id FROM t");
      assertEquals(1, result.getColumnCount());
      assertEquals(0, result.getRowCount());
      assertEquals("id", result.columnNames()[0]);
    }
  }

  @Test
  void testBulkFetchMixedTypes() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (i INTEGER, f FLOAT, s TEXT, b BOOLEAN, ts TIMESTAMP)");
      db.execute("INSERT INTO t VALUES (1, 2.5, 'abc', true, '2025-01-01 00:00:00')");
      db.execute("INSERT INTO t VALUES (2, NULL, NULL, false, NULL)");

      BulkDecoder.Result result = db.query("SELECT * FROM t ORDER BY i");
      assertEquals(5, result.getColumnCount());
      assertEquals(2, result.getRowCount());

      Object[] row0 = result.rows().get(0);
      assertEquals(1L, row0[0]);
      assertEquals(2.5, (Double) row0[1], 0.001);
      assertEquals("abc", row0[2]);
      assertEquals(true, row0[3]);
      assertNotNull(row0[4]); // timestamp

      Object[] row1 = result.rows().get(1);
      assertEquals(2L, row1[0]);
      assertNull(row1[1]);
      assertNull(row1[2]);
      assertEquals(false, row1[3]);
      assertNull(row1[4]);
    }
  }

  @Test
  void testBulkFetchLargeStrings() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, data TEXT)");
      String longString = "x".repeat(10_000);
      db.execute("INSERT INTO t VALUES ($1, $2)", 1L, longString);

      BulkDecoder.Result result = db.query("SELECT data FROM t");
      assertEquals(1, result.getRowCount());
      assertEquals(longString, result.rows().get(0)[0]);
    }
  }

  // --- SQL feature tests ---

  @Test
  void testJoinQuery() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
      db.execute("CREATE TABLE orders (id INTEGER, user_id INTEGER, amount FLOAT)");
      db.execute("INSERT INTO users VALUES (1, 'Alice')");
      db.execute("INSERT INTO users VALUES (2, 'Bob')");
      db.execute("INSERT INTO orders VALUES (1, 1, 99.99)");
      db.execute("INSERT INTO orders VALUES (2, 1, 50.00)");
      db.execute("INSERT INTO orders VALUES (3, 2, 75.50)");

      BulkDecoder.Result result =
          db.query(
              "SELECT u.name, SUM(o.amount) as total "
                  + "FROM users u JOIN orders o ON u.id = o.user_id "
                  + "GROUP BY u.name ORDER BY u.name");
      assertEquals(2, result.getRowCount());
      assertEquals("Alice", result.rows().get(0)[0]);
      assertEquals(149.99, (Double) result.rows().get(0)[1], 0.01);
      assertEquals("Bob", result.rows().get(1)[0]);
      assertEquals(75.50, (Double) result.rows().get(1)[1], 0.01);
    }
  }

  @Test
  void testSubquery() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, val INTEGER)");
      db.execute("INSERT INTO t VALUES (1, 10)");
      db.execute("INSERT INTO t VALUES (2, 20)");
      db.execute("INSERT INTO t VALUES (3, 30)");

      BulkDecoder.Result result =
          db.query("SELECT id FROM t WHERE val > (SELECT AVG(val) FROM t) ORDER BY id");
      assertEquals(1, result.getRowCount());
      assertEquals(3L, result.rows().get(0)[0]);
    }
  }

  @Test
  void testCTE() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, parent_id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, NULL, 'root')");
      db.execute("INSERT INTO t VALUES (2, 1, 'child1')");
      db.execute("INSERT INTO t VALUES (3, 1, 'child2')");

      BulkDecoder.Result result =
          db.query(
              "WITH children AS (SELECT * FROM t WHERE parent_id = 1) "
                  + "SELECT name FROM children ORDER BY name");
      assertEquals(2, result.getRowCount());
      assertEquals("child1", result.rows().get(0)[0]);
      assertEquals("child2", result.rows().get(1)[0]);
    }
  }

  @Test
  void testAggregations() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (category TEXT, val INTEGER)");
      db.execute("INSERT INTO t VALUES ('a', 10)");
      db.execute("INSERT INTO t VALUES ('a', 20)");
      db.execute("INSERT INTO t VALUES ('b', 30)");
      db.execute("INSERT INTO t VALUES ('b', 40)");
      db.execute("INSERT INTO t VALUES ('b', 50)");

      BulkDecoder.Result result =
          db.query(
              "SELECT category, COUNT(*), SUM(val), AVG(val), MIN(val), MAX(val) "
                  + "FROM t GROUP BY category ORDER BY category");
      assertEquals(2, result.getRowCount());

      Object[] rowA = result.rows().get(0);
      assertEquals("a", rowA[0]);
      assertEquals(2L, rowA[1]);
      assertEquals(30.0, ((Number) rowA[2]).doubleValue(), 0.01);
      assertEquals(15.0, ((Number) rowA[3]).doubleValue(), 0.01);
      assertEquals(10L, ((Number) rowA[4]).longValue());
      assertEquals(20L, ((Number) rowA[5]).longValue());

      Object[] rowB = result.rows().get(1);
      assertEquals("b", rowB[0]);
      assertEquals(3L, rowB[1]);
      assertEquals(120.0, ((Number) rowB[2]).doubleValue(), 0.01);
      assertEquals(40.0, ((Number) rowB[3]).doubleValue(), 0.01);
      assertEquals(30L, ((Number) rowB[4]).longValue());
      assertEquals(50L, ((Number) rowB[5]).longValue());
    }
  }

  @Test
  void testUnion() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t1 (id INTEGER, name TEXT)");
      db.execute("CREATE TABLE t2 (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t1 VALUES (1, 'a')");
      db.execute("INSERT INTO t1 VALUES (2, 'b')");
      db.execute("INSERT INTO t2 VALUES (3, 'c')");
      db.execute("INSERT INTO t2 VALUES (4, 'd')");

      BulkDecoder.Result result =
          db.query("SELECT id, name FROM t1 UNION ALL SELECT id, name FROM t2 ORDER BY id");
      assertEquals(4, result.getRowCount());
    }
  }

  @Test
  void testUpdateAndDelete() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, val INTEGER)");
      db.execute("INSERT INTO t VALUES (1, 10)");
      db.execute("INSERT INTO t VALUES (2, 20)");
      db.execute("INSERT INTO t VALUES (3, 30)");

      long updated = db.execute("UPDATE t SET val = 99 WHERE id = 2");
      assertEquals(1, updated);

      long deleted = db.execute("DELETE FROM t WHERE id = 3");
      assertEquals(1, deleted);

      BulkDecoder.Result result = db.query("SELECT id, val FROM t ORDER BY id");
      assertEquals(2, result.getRowCount());
      assertEquals(1L, result.rows().get(0)[0]);
      assertEquals(10L, result.rows().get(0)[1]);
      assertEquals(2L, result.rows().get(1)[0]);
      assertEquals(99L, result.rows().get(1)[1]);
    }
  }

  @Test
  void testLimitOffset() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER)");
      for (int i = 1; i <= 10; i++) {
        db.execute("INSERT INTO t VALUES (" + i + ")");
      }

      BulkDecoder.Result result = db.query("SELECT id FROM t ORDER BY id LIMIT 3 OFFSET 5");
      assertEquals(3, result.getRowCount());
      assertEquals(6L, result.rows().get(0)[0]);
      assertEquals(7L, result.rows().get(1)[0]);
      assertEquals(8L, result.rows().get(2)[0]);
    }
  }

  @Test
  void testDistinct() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (category TEXT)");
      db.execute("INSERT INTO t VALUES ('a')");
      db.execute("INSERT INTO t VALUES ('b')");
      db.execute("INSERT INTO t VALUES ('a')");
      db.execute("INSERT INTO t VALUES ('c')");
      db.execute("INSERT INTO t VALUES ('b')");

      BulkDecoder.Result result = db.query("SELECT DISTINCT category FROM t ORDER BY category");
      assertEquals(3, result.getRowCount());
      assertEquals("a", result.rows().get(0)[0]);
      assertEquals("b", result.rows().get(1)[0]);
      assertEquals("c", result.rows().get(2)[0]);
    }
  }

  @Test
  void testOrderByDesc() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'alpha')");
      db.execute("INSERT INTO t VALUES (2, 'beta')");
      db.execute("INSERT INTO t VALUES (3, 'gamma')");

      BulkDecoder.Result result = db.query("SELECT name FROM t ORDER BY id DESC");
      assertEquals(3, result.getRowCount());
      assertEquals("gamma", result.rows().get(0)[0]);
      assertEquals("beta", result.rows().get(1)[0]);
      assertEquals("alpha", result.rows().get(2)[0]);
    }
  }

  @Test
  void testCaseExpression() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, score INTEGER)");
      db.execute("INSERT INTO t VALUES (1, 95)");
      db.execute("INSERT INTO t VALUES (2, 75)");
      db.execute("INSERT INTO t VALUES (3, 50)");

      BulkDecoder.Result result =
          db.query(
              "SELECT id, CASE WHEN score >= 90 THEN 'A' "
                  + "WHEN score >= 70 THEN 'B' ELSE 'C' END AS grade "
                  + "FROM t ORDER BY id");
      assertEquals(3, result.getRowCount());
      assertEquals("A", result.rows().get(0)[1]);
      assertEquals("B", result.rows().get(1)[1]);
      assertEquals("C", result.rows().get(2)[1]);
    }
  }

  @Test
  void testCoalesce() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT, alias TEXT)");
      db.execute("INSERT INTO t VALUES (1, NULL, 'display1')");
      db.execute("INSERT INTO t VALUES (2, 'real', NULL)");

      BulkDecoder.Result result = db.query("SELECT COALESCE(name, alias) FROM t ORDER BY id");
      assertEquals(2, result.getRowCount());
      assertEquals("display1", result.rows().get(0)[0]);
      assertEquals("real", result.rows().get(1)[0]);
    }
  }

  @Test
  void testInClause() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'a')");
      db.execute("INSERT INTO t VALUES (2, 'b')");
      db.execute("INSERT INTO t VALUES (3, 'c')");
      db.execute("INSERT INTO t VALUES (4, 'd')");

      BulkDecoder.Result result = db.query("SELECT name FROM t WHERE id IN (1, 3) ORDER BY id");
      assertEquals(2, result.getRowCount());
      assertEquals("a", result.rows().get(0)[0]);
      assertEquals("c", result.rows().get(1)[0]);
    }
  }

  @Test
  void testLikePattern() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (name TEXT)");
      db.execute("INSERT INTO t VALUES ('apple')");
      db.execute("INSERT INTO t VALUES ('banana')");
      db.execute("INSERT INTO t VALUES ('apricot')");
      db.execute("INSERT INTO t VALUES ('cherry')");

      BulkDecoder.Result result =
          db.query("SELECT name FROM t WHERE name LIKE 'ap%' ORDER BY name");
      assertEquals(2, result.getRowCount());
      assertEquals("apple", result.rows().get(0)[0]);
      assertEquals("apricot", result.rows().get(1)[0]);
    }
  }

  @Test
  void testBetween() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, val INTEGER)");
      for (int i = 1; i <= 10; i++) {
        db.execute("INSERT INTO t VALUES (" + i + ", " + (i * 10) + ")");
      }

      BulkDecoder.Result result =
          db.query("SELECT id FROM t WHERE val BETWEEN 30 AND 70 ORDER BY id");
      assertEquals(5, result.getRowCount()); // 3,4,5,6,7
    }
  }

  // --- Concurrency tests ---

  @Test
  void testConcurrentClones() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, val INTEGER)");
      for (int i = 0; i < 100; i++) {
        db.execute("INSERT INTO t VALUES (" + i + ", " + (i * 10) + ")");
      }

      int numThreads = 4;
      CountDownLatch latch = new CountDownLatch(numThreads);
      AtomicInteger errors = new AtomicInteger(0);

      for (int t = 0; t < numThreads; t++) {
        final int threadId = t;
        Thread thread =
            new Thread(
                () -> {
                  try (StoolapDB clone = db.cloneHandle()) {
                    for (int i = 0; i < 25; i++) {
                      int queryId = threadId * 25 + i;
                      BulkDecoder.Result result =
                          clone.query("SELECT val FROM t WHERE id = " + queryId);
                      assertEquals(1, result.getRowCount());
                      assertEquals((long) queryId * 10, result.rows().get(0)[0]);
                    }
                  } catch (Exception e) {
                    errors.incrementAndGet();
                  } finally {
                    latch.countDown();
                  }
                });
        thread.start();
      }

      latch.await();
      assertEquals(0, errors.get());
    }
  }

  // --- Bulk insert performance ---

  @Test
  void testBulkInsertWithPreparedStatement() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT, score FLOAT)");

      try (StoolapStmt stmt = db.prepare("INSERT INTO t VALUES ($1, $2, $3)")) {
        try (StoolapTx tx = db.begin()) {
          for (int i = 0; i < 1000; i++) {
            tx.execute(stmt, (long) i, "user_" + i, (double) (i * 1.5));
          }
          tx.commit();
        }
      }

      BulkDecoder.Result countResult = db.query("SELECT COUNT(*) FROM t");
      assertEquals(1, countResult.getRowCount());
      assertEquals(1000L, countResult.rows().get(0)[0]);

      // Verify data integrity
      BulkDecoder.Result result = db.query("SELECT id, name, score FROM t ORDER BY id LIMIT 5");
      assertEquals(5, result.getRowCount());
      assertEquals(0L, result.rows().get(0)[0]);
      assertEquals("user_0", result.rows().get(0)[1]);
      assertEquals(0.0, (Double) result.rows().get(0)[2], 0.01);
      assertEquals(4L, result.rows().get(4)[0]);
      assertEquals("user_4", result.rows().get(4)[1]);
      assertEquals(6.0, (Double) result.rows().get(4)[2], 0.01);
    }
  }

  // --- DDL tests ---

  @Test
  void testAlterTableAddColumn() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER)");
      db.execute("INSERT INTO t VALUES (1)");
      db.execute("ALTER TABLE t ADD COLUMN name TEXT DEFAULT 'unknown'");

      BulkDecoder.Result result = db.query("SELECT id, name FROM t");
      assertEquals(1, result.getRowCount());
      assertEquals(1L, result.rows().get(0)[0]);
      assertEquals("unknown", result.rows().get(0)[1]);
    }
  }

  @Test
  void testCreateIndex() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.execute("CREATE INDEX idx_name ON t (name)");
      db.execute("INSERT INTO t VALUES (1, 'Alice')");
      db.execute("INSERT INTO t VALUES (2, 'Bob')");

      BulkDecoder.Result result = db.query("SELECT id FROM t WHERE name = 'Bob'");
      assertEquals(1, result.getRowCount());
      assertEquals(2L, result.rows().get(0)[0]);
    }
  }

  @Test
  void testDropTable() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER)");
      db.execute("DROP TABLE t");
      assertThrows(StoolapException.class, () -> db.query("SELECT * FROM t"));
    }
  }

  @Test
  void testTruncateTable() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER)");
      db.execute("INSERT INTO t VALUES (1)");
      db.execute("INSERT INTO t VALUES (2)");
      db.execute("TRUNCATE TABLE t");

      BulkDecoder.Result result = db.query("SELECT COUNT(*) FROM t");
      assertEquals(1, result.getRowCount());
      assertEquals(0L, result.rows().get(0)[0]);
    }
  }

  // --- Expression and function tests ---

  @Test
  void testStringFunctions() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      BulkDecoder.Result result =
          db.query("SELECT UPPER('hello'), LOWER('WORLD'), LENGTH('test'), TRIM('  hi  ')");
      assertEquals(1, result.getRowCount());
      Object[] row = result.rows().get(0);
      assertEquals("HELLO", row[0]);
      assertEquals("world", row[1]);
      assertEquals(4L, row[2]);
      assertEquals("hi", row[3]);
    }
  }

  @Test
  void testMathFunctions() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      BulkDecoder.Result result = db.query("SELECT ABS(-5), ROUND(3.7), CEIL(2.1), FLOOR(2.9)");
      assertEquals(1, result.getRowCount());
      Object[] row = result.rows().get(0);
      assertEquals(5L, row[0]);
      assertEquals(4.0, (Double) row[1], 0.01);
      assertEquals(3.0, (Double) row[2], 0.01);
      assertEquals(2.0, (Double) row[3], 0.01);
    }
  }

  @Test
  void testExistsSubquery() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)");
      db.execute("CREATE TABLE employees (id INTEGER, dept_id INTEGER, name TEXT)");
      db.execute("INSERT INTO departments VALUES (1, 'Engineering')");
      db.execute("INSERT INTO departments VALUES (2, 'Marketing')");
      db.execute("INSERT INTO employees VALUES (1, 1, 'Alice')");

      BulkDecoder.Result result =
          db.query(
              "SELECT d.name FROM departments d "
                  + "WHERE EXISTS (SELECT 1 FROM employees e WHERE e.dept_id = d.id) "
                  + "ORDER BY d.name");
      assertEquals(1, result.getRowCount());
      assertEquals("Engineering", result.rows().get(0)[0]);
    }
  }
}
