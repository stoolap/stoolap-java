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
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Named parameters, cursors, typed parameters and the error paths of the 0.5.0 driver. */
class DriverFeaturesTest {

  private static StoolapDB usersDb() throws Exception {
    StoolapDB db = StoolapDB.openInMemory();
    db.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
    db.execute("INSERT INTO users VALUES (1, 'Alice', 30), (2, 'Bob', 25), (3, 'Carol', 35)");
    return db;
  }

  private static StoolapDB numbersDb(int n) throws Exception {
    StoolapDB db = StoolapDB.openInMemory();
    db.execute("CREATE TABLE nums (id INTEGER PRIMARY KEY, label TEXT)");
    try (StoolapStmt ins = db.prepare("INSERT INTO nums VALUES ($1, $2)");
        StoolapTx tx = db.begin()) {
      for (int i = 1; i <= n; i++) tx.execute(ins, i, "n" + i);
      tx.commit();
    }
    return db;
  }

  // ---- Named parameters ----

  @Test
  void namedParametersOnDatabase() throws Exception {
    try (StoolapDB db = usersDb()) {
      BulkDecoder.Result r =
          db.query("SELECT name FROM users WHERE age > :min ORDER BY id", Map.of("min", 26));
      assertEquals(2, r.getRowCount());
      assertEquals("Alice", r.rows().get(0)[0]);

      assertEquals(
          1, db.execute("UPDATE users SET age = :age WHERE id = :id", Map.of("age", 31, "id", 1)));
      assertEquals(31L, db.query("SELECT age FROM users WHERE id = 1").rows().get(0)[0]);
    }
  }

  @Test
  void namedParameterPrefixIsIgnored() throws Exception {
    try (StoolapDB db = usersDb()) {
      for (String key : new String[] {":id", "@id", "$id", "id"}) {
        BulkDecoder.Result r = db.query("SELECT name FROM users WHERE id = :id", Map.of(key, 2));
        assertEquals("Bob", r.rows().get(0)[0], key);
      }
    }
  }

  @Test
  void namedParametersOnStatementAndTransaction() throws Exception {
    try (StoolapDB db = usersDb();
        StoolapStmt byId = db.prepare("SELECT name FROM users WHERE id = :id");
        StoolapStmt ins = db.prepare("INSERT INTO users VALUES (:id, :name, :age)")) {
      assertEquals("Carol", byId.query(Map.of("id", 3)).rows().get(0)[0]);
      assertEquals(1, ins.execute(Map.of("id", 4, "name", "Dan", "age", 40)));

      try (StoolapTx tx = db.begin()) {
        assertEquals(1, tx.execute(ins, Map.of("id", 5, "name", "Eve", "age", 22)));
        assertEquals("Eve", tx.query(byId, Map.of("id", 5)).rows().get(0)[0]);
        assertEquals(1, tx.execute("DELETE FROM users WHERE name = :name", Map.of("name", "Dan")));
        assertEquals(
            0L,
            tx.query("SELECT COUNT(*) FROM users WHERE id = :id", Map.of("id", 4))
                .rows()
                .get(0)[0]);
        tx.commit();
      }
      assertEquals(4L, db.query("SELECT COUNT(*) FROM users").rows().get(0)[0]);
    }
  }

  @Test
  void missingNamedParameterIsRefused() throws Exception {
    try (StoolapDB db = usersDb()) {
      assertThrows(
          StoolapException.class,
          () -> db.query("SELECT * FROM users WHERE id = :id AND age = :age", Map.of("id", 1)));
    }
  }

  // ---- Cursors ----

  @Test
  void cursorReadsEveryRowAcrossChunks() throws Exception {
    // 1024 is the chunk size: cover below, at and above a chunk boundary
    for (int n : new int[] {0, 1, 1023, 1024, 1025, 3000}) {
      try (StoolapDB db = numbersDb(n);
          StoolapCursor c = db.cursor("SELECT id, label FROM nums ORDER BY id")) {
        assertArrayEquals(new String[] {"id", "label"}, c.columnNames());
        int seen = 0;
        for (Object[] row; (row = c.next()) != null; ) {
          seen++;
          assertEquals((long) seen, row[0]);
          assertEquals("n" + seen, row[1]);
        }
        assertEquals(n, seen);
        assertNull(c.next());
      }
    }
  }

  @Test
  void cursorWithParametersStatementsAndTransactions() throws Exception {
    try (StoolapDB db = numbersDb(2500)) {
      try (StoolapCursor c = db.cursor("SELECT id FROM nums WHERE id > $1", 2000)) {
        assertEquals(500, drain(c));
      }
      try (StoolapCursor c = db.cursor("SELECT id FROM nums WHERE id <= :max", Map.of("max", 7))) {
        assertEquals(7, drain(c));
      }
      try (StoolapStmt s = db.prepare("SELECT id FROM nums WHERE id > $1")) {
        try (StoolapCursor c = s.cursor(100)) {
          assertEquals(2400, drain(c));
        }
        try (StoolapTx tx = db.begin()) {
          tx.execute("DELETE FROM nums WHERE id > 2000");
          try (StoolapCursor c = tx.cursor("SELECT id FROM nums")) {
            assertEquals(2000, drain(c));
          }
          try (StoolapCursor c = tx.cursor(s, 1000)) {
            assertEquals(1000, drain(c));
          }
          tx.rollback();
        }
      }
    }
  }

  @Test
  void cursorClosedEarlyReleasesItsResult() throws Exception {
    try (StoolapDB db = numbersDb(3000)) {
      StoolapCursor c = db.cursor("SELECT * FROM nums");
      assertNotNull(c.next());
      c.close();
      c.close();
      assertNull(c.next());
    }
  }

  private static int drain(StoolapCursor c) throws StoolapException {
    int n = 0;
    while (c.next() != null) n++;
    return n;
  }

  // ---- Typed parameters ----

  @Test
  void timestampParametersBindNatively() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER, ts TIMESTAMP)");
      Instant t = Instant.parse("2025-03-15T10:30:00.123456789Z");
      Instant before1970 = Instant.parse("1960-01-01T00:00:00.5Z");
      db.execute("INSERT INTO t VALUES ($1, $2)", 1, t);
      db.execute("INSERT INTO t VALUES ($1, $2)", 2, Timestamp.from(t));
      db.execute(
          "INSERT INTO t VALUES ($1, $2)", 3, OffsetDateTime.ofInstant(t, ZoneOffset.ofHours(3)));
      db.execute("INSERT INTO t VALUES ($1, $2)", 4, LocalDateTime.ofInstant(t, ZoneOffset.UTC));
      db.execute("INSERT INTO t VALUES ($1, $2)", 5, before1970);

      BulkDecoder.Result r = db.query("SELECT ts FROM t ORDER BY id");
      for (int i = 0; i < 4; i++) assertEquals(t, r.rows().get(i)[0], "row " + (i + 1));
      assertEquals(before1970, r.rows().get(4)[0]);
      assertEquals(1L, db.query("SELECT COUNT(*) FROM t WHERE ts < $1", t).rows().get(0)[0]);
    }
  }

  @Test
  void timestampOutsideTheStoredRangeIsRefused() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (ts TIMESTAMP)");
      StoolapException e =
          assertThrows(
              StoolapException.class,
              () -> db.execute("INSERT INTO t VALUES ($1)", Instant.parse("2300-01-01T00:00:00Z")));
      assertTrue(e.getMessage().contains("outside the range"), e.getMessage());
    }
  }

  @Test
  void vectorParametersAndResults() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE docs (id INTEGER PRIMARY KEY, emb VECTOR(3))");
      db.execute("INSERT INTO docs VALUES ($1, $2)", 1, new float[] {0.1f, 0.2f, 0.3f});
      db.execute("INSERT INTO docs VALUES (2, '[1.0, 0.0, 0.0]')");

      BulkDecoder.Result r = db.query("SELECT emb FROM docs ORDER BY id");
      assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f}, (float[]) r.rows().get(0)[0]);
      assertArrayEquals(new float[] {1f, 0f, 0f}, (float[]) r.rows().get(1)[0]);

      BulkDecoder.Result nearest =
          db.query(
              "SELECT id FROM docs ORDER BY VEC_DISTANCE_L2(emb, $1) LIMIT 1",
              new float[] {0.9f, 0f, 0f});
      assertEquals(2L, nearest.rows().get(0)[0]);
    }
  }

  @Test
  void jsonTextRoundTrips() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (doc JSON)");
      db.execute("INSERT INTO t VALUES ($1)", "{\"a\":1}");
      assertEquals("{\"a\":1}", db.query("SELECT doc FROM t").rows().get(0)[0]);
    }
  }

  @Test
  void byteArrayParameterIsRefused() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (s TEXT)");
      StoolapException e =
          assertThrows(
              StoolapException.class,
              () -> db.execute("INSERT INTO t VALUES ($1)", (Object) new byte[] {1, 2}));
      assertTrue(e.getMessage().contains("BLOB"), e.getMessage());
    }
  }

  @Test
  void textParameterKeepsNonAsciiCharacters() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (s TEXT)");
      String s = "ğüşıöç ✓ 漢字 😀";
      db.execute("INSERT INTO t VALUES ($1)", s);
      assertEquals(s, db.query("SELECT s FROM t WHERE s = $1", s).rows().get(0)[0]);
    }
  }

  // ---- Error paths ----

  @Test
  void runtimeErrorPartWayThroughAResultIsRaised() throws Exception {
    try (StoolapDB db = StoolapDB.openInMemory()) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, s TEXT)");
      db.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')");
      // The pattern is only compiled while rows are filtered, so the error ends the scan
      String sql = "SELECT id FROM t WHERE s REGEXP $1";
      assertThrows(StoolapException.class, () -> db.query(sql, "("));
      assertThrows(
          StoolapException.class,
          () -> {
            try (StoolapCursor c = db.cursor(sql, "(")) {
              drain(c);
            }
          });
    }
  }

  @Test
  void closedStatementIsRefusedInsteadOfCrashing() throws Exception {
    try (StoolapDB db = usersDb()) {
      StoolapStmt s = db.prepare("SELECT * FROM users");
      s.close();
      try (StoolapTx tx = db.begin()) {
        StoolapException e = assertThrows(StoolapException.class, () -> tx.query(s));
        assertTrue(e.getMessage().contains("closed"), e.getMessage());
      }
    }
  }

  @Test
  void transactionCanRollBackAfterUse() throws Exception {
    try (StoolapDB db = usersDb()) {
      StoolapTx tx = db.begin();
      tx.execute("DELETE FROM users");
      tx.rollback();
      tx.close();
      tx.close();
      assertEquals(3L, db.query("SELECT COUNT(*) FROM users").rows().get(0)[0]);
    }
  }

  // ---- JDBC ----

  @Test
  void jdbcBatchIsAtomicAndNamesTheFailingRow() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://");
        Statement st = conn.createStatement()) {
      st.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?)")) {
        for (int id : new int[] {1, 2, 2, 3}) {
          ps.setInt(1, id);
          ps.addBatch();
        }
        BatchUpdateException e = assertThrows(BatchUpdateException.class, ps::executeBatch);
        assertTrue(e.getMessage().contains("batch row 2"), e.getMessage());
      }
      try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
        assertTrue(rs.next());
        assertEquals(0, rs.getLong(1));
      }
    }
  }

  @Test
  void jdbcManyTransactionsInARow() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      try (Statement st = conn.createStatement()) {
        st.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      }
      conn.setAutoCommit(false);
      try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?)")) {
        for (int i = 0; i < 200; i++) {
          ps.setInt(1, i);
          ps.executeUpdate();
          if (i % 2 == 0) conn.commit();
          else conn.rollback();
        }
      }
      conn.setAutoCommit(true);
      try (Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
        assertTrue(rs.next());
        assertEquals(100, rs.getLong(1));
      }
    }
  }

  @Test
  void jdbcTimestampRoundTripsInAnyZone() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
      try (Statement st = conn.createStatement()) {
        st.execute("CREATE TABLE t (ts TIMESTAMP)");
      }
      Timestamp ts = Timestamp.from(Instant.parse("2025-06-01T12:00:00.250Z"));
      try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?)")) {
        ps.setTimestamp(1, ts);
        ps.executeUpdate();
      }
      try (Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery("SELECT ts FROM t")) {
        assertTrue(rs.next());
        assertEquals(ts, rs.getTimestamp(1));
      }
    }
  }

  @Test
  void jdbcVectorReadsAsFloatArrayAndText() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://");
        Statement st = conn.createStatement()) {
      st.execute("CREATE TABLE t (emb VECTOR(2))");
      try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?)")) {
        ps.setObject(1, new float[] {1.5f, -2f});
        ps.executeUpdate();
      }
      try (ResultSet rs = st.executeQuery("SELECT emb FROM t")) {
        assertTrue(rs.next());
        assertArrayEquals(new float[] {1.5f, -2f}, (float[]) rs.getObject(1));
        assertEquals("[1.5, -2.0]", rs.getString(1));
      }
    }
  }

  @Test
  void jdbcSetBytesIsRefused() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://");
        Statement st = conn.createStatement()) {
      st.execute("CREATE TABLE t (s TEXT)");
      try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?)")) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> ps.setBytes(1, new byte[] {1}));
      }
    }
  }
}
