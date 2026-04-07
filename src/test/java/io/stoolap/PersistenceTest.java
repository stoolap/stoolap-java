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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * File-based persistence tests (mirrors Python test_persistence.py).
 *
 * <p>Each test opens a file-based database, writes data, closes it, reopens it, and verifies the
 * data survived the close/reopen cycle.
 */
class PersistenceTest {

  private Path dbDir;

  @BeforeEach
  void setUp() throws IOException {
    dbDir = Files.createTempDirectory("stoolap_test_");
  }

  @AfterEach
  void tearDown() throws IOException {
    if (dbDir != null && Files.exists(dbDir)) {
      try (Stream<Path> walk = Files.walk(dbDir)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.delete(p);
                  } catch (IOException e) {
                    // best effort
                  }
                });
      }
    }
  }

  private String dsn(String name) {
    return "file://" + dbDir.resolve(name).toString();
  }

  @Test
  void testFilePersistenceBasic() throws Exception {
    String dsn = dsn("testdb");

    try (StoolapDB db = StoolapDB.open(dsn)) {
      db.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
      db.execute("INSERT INTO users VALUES ($1, $2)", 1L, "Alice");
      db.execute("INSERT INTO users VALUES ($1, $2)", 2L, "Bob");
    }

    // Reopen and verify
    try (StoolapDB db = StoolapDB.open(dsn)) {
      BulkDecoder.Result result = db.query("SELECT * FROM users ORDER BY id");
      assertEquals(2, result.getRowCount());
      assertEquals("Alice", result.rows().get(0)[1]);
      assertEquals("Bob", result.rows().get(1)[1]);
    }
  }

  @Test
  void testFilePersistenceWithIndex() throws Exception {
    String dsn = dsn("testdb");

    try (StoolapDB db = StoolapDB.open(dsn)) {
      db.execute("CREATE TABLE products (id INTEGER PRIMARY KEY, name TEXT, price FLOAT)");
      db.execute("CREATE INDEX idx_products_name ON products(name)");
      db.execute("INSERT INTO products VALUES ($1, $2, $3)", 1L, "Widget", 9.99);
      db.execute("INSERT INTO products VALUES ($1, $2, $3)", 2L, "Gadget", 19.99);
    }

    try (StoolapDB db = StoolapDB.open(dsn)) {
      BulkDecoder.Result result = db.query("SELECT * FROM products WHERE name = $1", "Widget");
      assertEquals(1, result.getRowCount());
      assertEquals(9.99, (Double) result.rows().get(0)[2], 0.001);
    }
  }

  @Test
  void testFilePersistenceUpdateDelete() throws Exception {
    String dsn = dsn("testdb");

    try (StoolapDB db = StoolapDB.open(dsn)) {
      db.execute("CREATE TABLE kv (k INTEGER PRIMARY KEY, v TEXT)");
      db.execute("INSERT INTO kv VALUES ($1, $2)", 1L, "original");
      db.execute("INSERT INTO kv VALUES ($1, $2)", 2L, "delete_me");
      db.execute("UPDATE kv SET v = $1 WHERE k = $2", "updated", 1L);
      db.execute("DELETE FROM kv WHERE k = $1", 2L);
    }

    try (StoolapDB db = StoolapDB.open(dsn)) {
      BulkDecoder.Result result = db.query("SELECT * FROM kv ORDER BY k");
      assertEquals(1, result.getRowCount());
      assertEquals("updated", result.rows().get(0)[1]);
    }
  }

  @Test
  void testFilePersistenceMultipleTables() throws Exception {
    String dsn = dsn("testdb");

    try (StoolapDB db = StoolapDB.open(dsn)) {
      db.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
      db.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, amount FLOAT)");
      db.execute("INSERT INTO users VALUES ($1, $2)", 1L, "Alice");
      db.execute("INSERT INTO orders VALUES ($1, $2, $3)", 1L, 1L, 99.99);
      db.execute("INSERT INTO orders VALUES ($1, $2, $3)", 2L, 1L, 49.99);
    }

    try (StoolapDB db = StoolapDB.open(dsn)) {
      BulkDecoder.Result users = db.query("SELECT * FROM users");
      BulkDecoder.Result orders = db.query("SELECT * FROM orders ORDER BY id");
      assertEquals(1, users.getRowCount());
      assertEquals(2, orders.getRowCount());
      assertEquals(99.99, (Double) orders.rows().get(0)[2], 0.001);
    }
  }

  @Test
  void testFilePersistenceTransaction() throws Exception {
    String dsn = dsn("testdb");

    try (StoolapDB db = StoolapDB.open(dsn)) {
      db.execute("CREATE TABLE counter (id INTEGER PRIMARY KEY, val INTEGER)");

      // Committed transaction
      try (StoolapTx tx = db.begin()) {
        tx.execute("INSERT INTO counter VALUES ($1, $2)", 1L, 100L);
        tx.commit();
      }

      // Rolled-back transaction
      try (StoolapTx tx = db.begin()) {
        tx.execute("INSERT INTO counter VALUES ($1, $2)", 2L, 200L);
        tx.rollback();
      }
    }

    try (StoolapDB db = StoolapDB.open(dsn)) {
      BulkDecoder.Result result = db.query("SELECT * FROM counter ORDER BY id");
      assertEquals(1, result.getRowCount());
      assertEquals(100L, result.rows().get(0)[1]);
    }
  }

  @Test
  void testFilePersistenceBatchInsert() throws Exception {
    String dsn = dsn("testdb");

    try (StoolapDB db = StoolapDB.open(dsn)) {
      db.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)");
      try (StoolapStmt stmt = db.prepare("INSERT INTO items VALUES ($1, $2)")) {
        try (StoolapTx tx = db.begin()) {
          for (int i = 0; i < 100; i++) {
            tx.execute(stmt, (long) i, "item_" + i);
          }
          tx.commit();
        }
      }
    }

    try (StoolapDB db = StoolapDB.open(dsn)) {
      BulkDecoder.Result result = db.query("SELECT COUNT(*) FROM items");
      assertEquals(100L, result.rows().get(0)[0]);
    }
  }

  @Test
  void testFilePersistenceDsnOptions() throws Exception {
    String path = dbDir.resolve("testdb").toString();
    String dsn = "file://" + path + "?sync_mode=full";

    try (StoolapDB db = StoolapDB.open(dsn)) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, val TEXT)");
      db.execute("INSERT INTO t VALUES ($1, $2)", 1L, "durable");
    }

    try (StoolapDB db = StoolapDB.open("file://" + path)) {
      BulkDecoder.Result result = db.query("SELECT val FROM t WHERE id = $1", 1L);
      assertEquals(1, result.getRowCount());
      assertEquals("durable", result.rows().get(0)[0]);
    }
  }

  @Test
  void testFilePersistenceCheckpointOptions() throws Exception {
    String path = dbDir.resolve("testdb").toString();
    String dsn =
        "file://" + path + "?checkpoint_interval=30&compact_threshold=2&checkpoint_on_close=on";

    try (StoolapDB db = StoolapDB.open(dsn)) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, val TEXT)");
      db.execute("INSERT INTO t VALUES ($1, $2)", 1L, "checkpoint_test");
    }

    try (StoolapDB db = StoolapDB.open("file://" + path)) {
      BulkDecoder.Result result = db.query("SELECT val FROM t WHERE id = $1", 1L);
      assertEquals(1, result.getRowCount());
      assertEquals("checkpoint_test", result.rows().get(0)[0]);
    }
  }

  @Test
  void testFilePersistenceVolumeCompression() throws Exception {
    String path = dbDir.resolve("testdb").toString();
    String dsn = "file://" + path + "?wal_compression=on&volume_compression=on";

    try (StoolapDB db = StoolapDB.open(dsn)) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, val TEXT)");
      for (int i = 0; i < 50; i++) {
        db.execute("INSERT INTO t VALUES ($1, $2)", (long) i, "row_" + i);
      }
    }

    try (StoolapDB db = StoolapDB.open("file://" + path)) {
      BulkDecoder.Result result = db.query("SELECT COUNT(*) FROM t");
      assertEquals(50L, result.rows().get(0)[0]);
    }
  }

  @Test
  void testRelativePathPersistence() throws Exception {
    // Use path without file:// prefix (the driver should accept absolute paths directly)
    String path = dbDir.resolve("reldb").toString();

    try (StoolapDB db = StoolapDB.open("file://" + path)) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      db.execute("INSERT INTO t VALUES ($1)", 1L);
    }

    try (StoolapDB db = StoolapDB.open("file://" + path)) {
      BulkDecoder.Result result = db.query("SELECT * FROM t");
      assertEquals(1, result.getRowCount());
    }
  }

  // --- JDBC equivalent ---

  @Test
  void testJdbcFilePersistence() throws Exception {
    String url = "jdbc:stoolap:file://" + dbDir.resolve("jdbcdb").toString();

    try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url);
        java.sql.Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
      stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice')");
      stmt.executeUpdate("INSERT INTO users VALUES (2, 'Bob')");
    }

    try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url);
        java.sql.Statement stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery("SELECT id, name FROM users ORDER BY id")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt("id"));
      assertEquals("Alice", rs.getString("name"));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt("id"));
      assertEquals("Bob", rs.getString("name"));
      assertFalse(rs.next());
    }
  }
}
