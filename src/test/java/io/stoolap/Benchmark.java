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

import java.sql.*;

/**
 * Stoolap vs SQLite (JDBC) benchmark.
 *
 * <p>Both drivers use synchronous JDBC methods for fair comparison. Matches the Python benchmark.py
 * test set and ordering exactly.
 *
 * <p>Run: mvn exec:java -Dexec.mainClass="io.stoolap.Benchmark" \ -Dexec.classpathScope=test
 */
public final class Benchmark {

  static final int ROW_COUNT = 10_000;
  static final int ITERATIONS = 500; // Point queries
  static final int ITERATIONS_MED = 250; // Index scans, aggregations
  static final int ITERATIONS_HEAVY = 50; // Full scans, JOINs
  static final int WARMUP = 10;

  // ================================================================
  // Helpers
  // ================================================================

  static int stoolapWins = 0;
  static int sqliteWins = 0;

  static int seedRandom(int i) {
    return (i * 1103515245 + 12345) & 0x7FFFFFFF;
  }

  static String fmtUs(double us) {
    return String.format("%15.3f", us);
  }

  static String fmtRatio(double stoolapUs, double sqliteUs) {
    if (stoolapUs <= 0 || sqliteUs <= 0) return "      -";
    double ratio = sqliteUs / stoolapUs;
    if (ratio >= 1) {
      return String.format("%9.2fx", ratio);
    } else {
      return String.format("%8.2fx*", 1.0 / ratio);
    }
  }

  static void printRow(String name, double stoolapUs, double sqliteUs) {
    if (stoolapUs < sqliteUs) stoolapWins++;
    else if (sqliteUs < stoolapUs) sqliteWins++;
    System.out.printf(
        "%-28s | %s | %s | %s%n",
        name, fmtUs(stoolapUs), fmtUs(sqliteUs), fmtRatio(stoolapUs, sqliteUs));
  }

  static void printHeader(String section) {
    System.out.println();
    System.out.println("=".repeat(80));
    System.out.println(section);
    System.out.println("=".repeat(80));
    System.out.printf(
        "%-28s | %15s | %15s | %10s%n", "Operation", "Stoolap (μs)", "SQLite (μs)", "Ratio");
    System.out.println("-".repeat(80));
  }

  /** Drain a ResultSet so all rows are fetched. Returns row count. */
  static int drain(ResultSet rs) throws SQLException {
    int colCount = rs.getMetaData().getColumnCount();
    int n = 0;
    while (rs.next()) {
      for (int c = 1; c <= colCount; c++) rs.getObject(c);
      n++;
    }
    rs.close();
    return n;
  }

  // ================================================================
  // Main
  // ================================================================

  public static void main(String[] args) throws Exception {
    System.out.println("Stoolap vs SQLite (JDBC) — Java Benchmark");
    System.out.printf("Configuration: %d rows, %d iterations per test%n", ROW_COUNT, ITERATIONS);
    System.out.println("All operations are synchronous — fair comparison");
    System.out.println("Ratio > 1x = Stoolap faster  |  * = SQLite faster");
    System.out.println();

    // --- Stoolap setup ---
    Connection sConn = DriverManager.getConnection("jdbc:stoolap:memory://");
    Statement sStmt = sConn.createStatement();
    sStmt.execute(
        """
        CREATE TABLE users (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            email TEXT NOT NULL,
            age INTEGER NOT NULL,
            balance FLOAT NOT NULL,
            active BOOLEAN NOT NULL,
            created_at TEXT NOT NULL
        )\
        """);
    sStmt.execute("CREATE INDEX idx_users_age ON users(age)");
    sStmt.execute("CREATE INDEX idx_users_active ON users(active)");

    // --- SQLite setup ---
    Connection lConn = DriverManager.getConnection("jdbc:sqlite::memory:");
    Statement lStmt = lConn.createStatement();
    lStmt.execute("PRAGMA journal_mode=WAL");
    lStmt.execute(
        """
        CREATE TABLE users (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            email TEXT NOT NULL,
            age INTEGER NOT NULL,
            balance REAL NOT NULL,
            active INTEGER NOT NULL,
            created_at TEXT NOT NULL
        )\
        """);
    lStmt.execute("CREATE INDEX idx_users_age ON users(age)");
    lStmt.execute("CREATE INDEX idx_users_active ON users(active)");

    // --- Populate users ---
    // Pre-compute all row data (avoid allocation during benchmarks)
    long[] rowId = new long[ROW_COUNT];
    String[] rowName = new String[ROW_COUNT];
    String[] rowEmail = new String[ROW_COUNT];
    long[] rowAge = new long[ROW_COUNT];
    double[] rowBalance = new double[ROW_COUNT];
    long[] rowActive = new long[ROW_COUNT];

    for (int i = 0; i < ROW_COUNT; i++) {
      int idx = i + 1;
      rowId[i] = idx;
      rowName[i] = "User_" + idx;
      rowEmail[i] = "user" + idx + "@example.com";
      rowAge[i] = (seedRandom(idx) % 62) + 18;
      rowBalance[i] = (seedRandom(idx * 7) % 100000) + (seedRandom(idx * 13) % 100) / 100.0;
      rowActive[i] = seedRandom(idx * 3) % 10 < 7 ? 1 : 0;
    }

    // SQLite bulk insert (explicit transaction for setup)
    lConn.setAutoCommit(false);
    PreparedStatement lIns =
        lConn.prepareStatement(
            "INSERT INTO users (id, name, email, age, balance, active, created_at) VALUES (?, ?, ?,"
                + " ?, ?, ?, ?)");
    for (int i = 0; i < ROW_COUNT; i++) {
      lIns.setLong(1, rowId[i]);
      lIns.setString(2, rowName[i]);
      lIns.setString(3, rowEmail[i]);
      lIns.setLong(4, rowAge[i]);
      lIns.setDouble(5, rowBalance[i]);
      lIns.setLong(6, rowActive[i]);
      lIns.setString(7, "2024-01-01 00:00:00");
      lIns.addBatch();
    }
    lIns.executeBatch();
    lConn.commit();
    lConn.setAutoCommit(true);
    lIns.close();

    // Stoolap bulk insert
    PreparedStatement sIns =
        sConn.prepareStatement(
            "INSERT INTO users (id, name, email, age, balance, active, created_at) VALUES ($1, $2,"
                + " $3, $4, $5, $6, $7)");
    for (int i = 0; i < ROW_COUNT; i++) {
      sIns.setLong(1, rowId[i]);
      sIns.setString(2, rowName[i]);
      sIns.setString(3, rowEmail[i]);
      sIns.setLong(4, rowAge[i]);
      sIns.setDouble(5, rowBalance[i]);
      sIns.setLong(6, rowActive[i]);
      sIns.setString(7, "2024-01-01 00:00:00");
      sIns.addBatch();
    }
    sIns.executeBatch();
    sIns.close();

    // ============================================================
    // CORE OPERATIONS
    // ============================================================
    printHeader("CORE OPERATIONS");

    // Pre-compute lookup arrays (avoid allocation in hot loop)
    int[] ids = new int[ITERATIONS];
    int[] ages = new int[ITERATIONS];
    for (int i = 0; i < ITERATIONS; i++) {
      ids[i] = (i % ROW_COUNT) + 1;
      ages[i] = (i % 62) + 18;
    }

    // --- SELECT by ID ---
    {
      PreparedStatement sPs = sConn.prepareStatement("SELECT * FROM users WHERE id = $1");
      PreparedStatement lPs = lConn.prepareStatement("SELECT * FROM users WHERE id = ?");

      for (int i = 0; i < WARMUP; i++) {
        sPs.setLong(1, ids[i]);
        drain(sPs.executeQuery());
        lPs.setLong(1, ids[i]);
        drain(lPs.executeQuery());
      }

      long t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        sPs.setLong(1, ids[i]);
        drain(sPs.executeQuery());
      }
      double sUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        lPs.setLong(1, ids[i]);
        drain(lPs.executeQuery());
      }
      double lUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      printRow("SELECT by ID", sUs, lUs);
      sPs.close();
      lPs.close();
    }

    // --- SELECT by index (exact) ---
    {
      PreparedStatement sPs = sConn.prepareStatement("SELECT * FROM users WHERE age = $1");
      PreparedStatement lPs = lConn.prepareStatement("SELECT * FROM users WHERE age = ?");

      for (int i = 0; i < WARMUP; i++) {
        sPs.setLong(1, ages[i]);
        drain(sPs.executeQuery());
        lPs.setLong(1, ages[i]);
        drain(lPs.executeQuery());
      }

      long t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        sPs.setLong(1, ages[i]);
        drain(sPs.executeQuery());
      }
      double sUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        lPs.setLong(1, ages[i]);
        drain(lPs.executeQuery());
      }
      double lUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      printRow("SELECT by index (exact)", sUs, lUs);
      sPs.close();
      lPs.close();
    }

    // --- SELECT by index (range) ---
    {
      PreparedStatement sPs =
          sConn.prepareStatement("SELECT * FROM users WHERE age >= $1 AND age <= $2");
      PreparedStatement lPs =
          lConn.prepareStatement("SELECT * FROM users WHERE age >= ? AND age <= ?");

      for (int i = 0; i < WARMUP; i++) {
        sPs.setLong(1, 30);
        sPs.setLong(2, 40);
        drain(sPs.executeQuery());
        lPs.setLong(1, 30);
        lPs.setLong(2, 40);
        drain(lPs.executeQuery());
      }

      long t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        sPs.setLong(1, 30);
        sPs.setLong(2, 40);
        drain(sPs.executeQuery());
      }
      double sUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        lPs.setLong(1, 30);
        lPs.setLong(2, 40);
        drain(lPs.executeQuery());
      }
      double lUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      printRow("SELECT by index (range)", sUs, lUs);
      sPs.close();
      lPs.close();
    }

    // --- SELECT complex ---
    benchQuery(
        sConn,
        lConn,
        "SELECT id, name, balance FROM users WHERE age >= 25 AND age <= 45 AND active = true ORDER"
            + " BY balance DESC LIMIT 100",
        "SELECT id, name, balance FROM users WHERE age >= 25 AND age <= 45 AND active = 1 ORDER BY"
            + " balance DESC LIMIT 100",
        "SELECT complex",
        ITERATIONS);

    // --- SELECT * (full scan) ---
    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users",
        "SELECT * FROM users",
        "SELECT * (full scan)",
        ITERATIONS_HEAVY);

    // --- UPDATE by ID ---
    {
      PreparedStatement sPs = sConn.prepareStatement("UPDATE users SET balance = $1 WHERE id = $2");
      PreparedStatement lPs = lConn.prepareStatement("UPDATE users SET balance = ? WHERE id = ?");

      double[] balances = new double[ITERATIONS];
      int[] updIds = new int[ITERATIONS];
      for (int i = 0; i < ITERATIONS; i++) {
        balances[i] = (seedRandom(i * 17) % 100000) + 0.5;
        updIds[i] = (i % ROW_COUNT) + 1;
      }

      for (int i = 0; i < WARMUP; i++) {
        sPs.setDouble(1, balances[i]);
        sPs.setLong(2, updIds[i]);
        sPs.executeUpdate();
        lPs.setDouble(1, balances[i]);
        lPs.setLong(2, updIds[i]);
        lPs.executeUpdate();
      }

      long t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        sPs.setDouble(1, balances[i]);
        sPs.setLong(2, updIds[i]);
        sPs.executeUpdate();
      }
      double sUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        lPs.setDouble(1, balances[i]);
        lPs.setLong(2, updIds[i]);
        lPs.executeUpdate();
      }
      double lUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      printRow("UPDATE by ID", sUs, lUs);
      sPs.close();
      lPs.close();
    }

    // --- UPDATE complex ---
    {
      PreparedStatement sPs =
          sConn.prepareStatement(
              "UPDATE users SET balance = $1 WHERE age >= $2 AND age <= $3 AND active = true");
      PreparedStatement lPs =
          lConn.prepareStatement(
              "UPDATE users SET balance = ? WHERE age >= ? AND age <= ? AND active = 1");

      double[] balances = new double[ITERATIONS];
      for (int i = 0; i < ITERATIONS; i++) balances[i] = (seedRandom(i * 23) % 100000) + 0.5;

      for (int i = 0; i < WARMUP; i++) {
        sPs.setDouble(1, balances[i]);
        sPs.setLong(2, 27);
        sPs.setLong(3, 28);
        sPs.executeUpdate();
        lPs.setDouble(1, balances[i]);
        lPs.setLong(2, 27);
        lPs.setLong(3, 28);
        lPs.executeUpdate();
      }

      long t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        sPs.setDouble(1, balances[i]);
        sPs.setLong(2, 27);
        sPs.setLong(3, 28);
        sPs.executeUpdate();
      }
      double sUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        lPs.setDouble(1, balances[i]);
        lPs.setLong(2, 27);
        lPs.setLong(3, 28);
        lPs.executeUpdate();
      }
      double lUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      printRow("UPDATE complex", sUs, lUs);
      sPs.close();
      lPs.close();
    }

    // --- INSERT single ---
    {
      PreparedStatement sPs =
          sConn.prepareStatement(
              "INSERT INTO users (id, name, email, age, balance, active, created_at) VALUES ($1,"
                  + " $2, $3, $4, $5, $6, $7)");
      PreparedStatement lPs =
          lConn.prepareStatement(
              "INSERT INTO users (id, name, email, age, balance, active, created_at) VALUES (?, ?,"
                  + " ?, ?, ?, ?, ?)");
      int base = ROW_COUNT + 1000;

      // Pre-compute strings
      String[] insNames = new String[ITERATIONS];
      String[] insEmails = new String[ITERATIONS];
      long[] insAges = new long[ITERATIONS];
      for (int i = 0; i < ITERATIONS; i++) {
        int id_ = base + i;
        insNames[i] = "New_" + id_;
        insEmails[i] = "new" + id_ + "@example.com";
        insAges[i] = (seedRandom(i * 29) % 62) + 18;
      }

      long t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        int id_ = base + i;
        sPs.setLong(1, id_);
        sPs.setString(2, insNames[i]);
        sPs.setString(3, insEmails[i]);
        sPs.setLong(4, insAges[i]);
        sPs.setDouble(5, 100.0);
        sPs.setLong(6, 1);
        sPs.setString(7, "2024-01-01 00:00:00");
        sPs.executeUpdate();
      }
      double sUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      // Pre-compute for SQLite with offset
      String[] insNames2 = new String[ITERATIONS];
      String[] insEmails2 = new String[ITERATIONS];
      for (int i = 0; i < ITERATIONS; i++) {
        int id_ = base + ITERATIONS + i;
        insNames2[i] = "New_" + id_;
        insEmails2[i] = "new" + id_ + "@example.com";
      }

      t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        int id_ = base + ITERATIONS + i;
        lPs.setLong(1, id_);
        lPs.setString(2, insNames2[i]);
        lPs.setString(3, insEmails2[i]);
        lPs.setLong(4, insAges[i]);
        lPs.setDouble(5, 100.0);
        lPs.setLong(6, 1);
        lPs.setString(7, "2024-01-01 00:00:00");
        lPs.executeUpdate();
      }
      double lUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      printRow("INSERT single", sUs, lUs);
      sPs.close();
      lPs.close();
    }

    // --- DELETE by ID ---
    {
      PreparedStatement sPs = sConn.prepareStatement("DELETE FROM users WHERE id = $1");
      PreparedStatement lPs = lConn.prepareStatement("DELETE FROM users WHERE id = ?");
      int base = ROW_COUNT + 1000;

      long t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        sPs.setLong(1, base + i);
        sPs.executeUpdate();
      }
      double sUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        lPs.setLong(1, base + ITERATIONS + i);
        lPs.executeUpdate();
      }
      double lUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      printRow("DELETE by ID", sUs, lUs);
      sPs.close();
      lPs.close();
    }

    // --- DELETE complex ---
    {
      PreparedStatement sPs =
          sConn.prepareStatement(
              "DELETE FROM users WHERE age >= $1 AND age <= $2 AND active = true");
      PreparedStatement lPs =
          lConn.prepareStatement("DELETE FROM users WHERE age >= ? AND age <= ? AND active = 1");

      long t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        sPs.setLong(1, 25);
        sPs.setLong(2, 26);
        sPs.executeUpdate();
      }
      double sUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      t0 = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        lPs.setLong(1, 25);
        lPs.setLong(2, 26);
        lPs.executeUpdate();
      }
      double lUs = (System.nanoTime() - t0) / 1000.0 / ITERATIONS;

      printRow("DELETE complex", sUs, lUs);
      sPs.close();
      lPs.close();
    }

    // --- Aggregation (GROUP BY) ---
    benchQuery(
        sConn,
        lConn,
        "SELECT age, COUNT(*), AVG(balance) FROM users GROUP BY age",
        "SELECT age, COUNT(*), AVG(balance) FROM users GROUP BY age",
        "Aggregation (GROUP BY)",
        ITERATIONS_MED);

    // ============================================================
    // ADVANCED OPERATIONS
    // ============================================================

    // Create orders table
    sStmt.execute(
        """
        CREATE TABLE orders (
            id INTEGER PRIMARY KEY,
            user_id INTEGER NOT NULL,
            amount FLOAT NOT NULL,
            status TEXT NOT NULL,
            order_date TEXT NOT NULL
        )\
        """);
    sStmt.execute("CREATE INDEX idx_orders_user_id ON orders(user_id)");
    sStmt.execute("CREATE INDEX idx_orders_status ON orders(status)");

    lStmt.execute(
        """
        CREATE TABLE orders (
            id INTEGER PRIMARY KEY,
            user_id INTEGER NOT NULL,
            amount REAL NOT NULL,
            status TEXT NOT NULL,
            order_date TEXT NOT NULL
        )\
        """);
    lStmt.execute("CREATE INDEX idx_orders_user_id ON orders(user_id)");
    lStmt.execute("CREATE INDEX idx_orders_status ON orders(status)");

    // Populate orders (3 per user on average)
    String[] statuses = {"pending", "completed", "shipped", "cancelled"};
    int orderCount = ROW_COUNT * 3;

    lConn.setAutoCommit(false);
    PreparedStatement lOrdIns =
        lConn.prepareStatement(
            "INSERT INTO orders (id, user_id, amount, status, order_date) VALUES (?, ?, ?, ?, ?)");
    PreparedStatement sOrdIns =
        sConn.prepareStatement(
            "INSERT INTO orders (id, user_id, amount, status, order_date) VALUES ($1, $2, $3, $4,"
                + " $5)");

    for (int i = 1; i <= orderCount; i++) {
      int userId = (seedRandom(i * 11) % ROW_COUNT) + 1;
      double amount = (seedRandom(i * 19) % 990) + 10 + (seedRandom(i * 23) % 100) / 100.0;
      String status = statuses[seedRandom(i * 31) % 4];

      lOrdIns.setLong(1, i);
      lOrdIns.setLong(2, userId);
      lOrdIns.setDouble(3, amount);
      lOrdIns.setString(4, status);
      lOrdIns.setString(5, "2024-01-15");
      lOrdIns.addBatch();

      sOrdIns.setLong(1, i);
      sOrdIns.setLong(2, userId);
      sOrdIns.setDouble(3, amount);
      sOrdIns.setString(4, status);
      sOrdIns.setString(5, "2024-01-15");
      sOrdIns.addBatch();
    }
    lOrdIns.executeBatch();
    lConn.commit();
    lConn.setAutoCommit(true);
    lOrdIns.close();
    sOrdIns.executeBatch();
    sOrdIns.close();

    printHeader("ADVANCED OPERATIONS");

    // --- INNER JOIN ---
    benchQuery(
        sConn,
        lConn,
        "SELECT u.name, o.amount FROM users u INNER JOIN orders o ON u.id = o.user_id WHERE"
            + " o.status = 'completed' LIMIT 100",
        "SELECT u.name, o.amount FROM users u INNER JOIN orders o ON u.id = o.user_id WHERE"
            + " o.status = 'completed' LIMIT 100",
        "INNER JOIN",
        100);

    // --- LEFT JOIN + GROUP BY ---
    benchQuery(
        sConn,
        lConn,
        "SELECT u.name, COUNT(o.id) as order_count, SUM(o.amount) as total FROM users u LEFT JOIN"
            + " orders o ON u.id = o.user_id GROUP BY u.id, u.name LIMIT 100",
        "SELECT u.name, COUNT(o.id) as order_count, SUM(o.amount) as total FROM users u LEFT JOIN"
            + " orders o ON u.id = o.user_id GROUP BY u.id, u.name LIMIT 100",
        "LEFT JOIN + GROUP BY",
        100);

    // --- Scalar subquery ---
    benchQuery(
        sConn,
        lConn,
        "SELECT name, balance, (SELECT AVG(balance) FROM users) as avg_balance FROM users WHERE"
            + " balance > (SELECT AVG(balance) FROM users) LIMIT 100",
        "SELECT name, balance, (SELECT AVG(balance) FROM users) as avg_balance FROM users WHERE"
            + " balance > (SELECT AVG(balance) FROM users) LIMIT 100",
        "Scalar subquery",
        ITERATIONS);

    // --- IN subquery ---
    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE status = 'completed')"
            + " LIMIT 100",
        "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE status = 'completed')"
            + " LIMIT 100",
        "IN subquery",
        10);

    // --- EXISTS subquery ---
    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND"
            + " o.amount > 500) LIMIT 100",
        "SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND"
            + " o.amount > 500) LIMIT 100",
        "EXISTS subquery",
        100);

    // --- CTE + JOIN ---
    benchQuery(
        sConn,
        lConn,
        "WITH high_value AS (SELECT user_id, SUM(amount) as total FROM orders GROUP BY user_id"
            + " HAVING SUM(amount) > 1000) SELECT u.name, h.total FROM users u INNER JOIN"
            + " high_value h ON u.id = h.user_id LIMIT 100",
        "WITH high_value AS (SELECT user_id, SUM(amount) as total FROM orders GROUP BY user_id"
            + " HAVING SUM(amount) > 1000) SELECT u.name, h.total FROM users u INNER JOIN"
            + " high_value h ON u.id = h.user_id LIMIT 100",
        "CTE + JOIN",
        20);

    // --- Window ROW_NUMBER ---
    benchQuery(
        sConn,
        lConn,
        "SELECT name, balance, ROW_NUMBER() OVER (ORDER BY balance DESC) as rank FROM users LIMIT"
            + " 100",
        "SELECT name, balance, ROW_NUMBER() OVER (ORDER BY balance DESC) as rank FROM users LIMIT"
            + " 100",
        "Window ROW_NUMBER",
        ITERATIONS);

    // --- Window ROW_NUMBER (PK) ---
    benchQuery(
        sConn,
        lConn,
        "SELECT name, ROW_NUMBER() OVER (ORDER BY id) as rank FROM users LIMIT 100",
        "SELECT name, ROW_NUMBER() OVER (ORDER BY id) as rank FROM users LIMIT 100",
        "Window ROW_NUMBER (PK)",
        ITERATIONS);

    // --- Window PARTITION BY ---
    benchQuery(
        sConn,
        lConn,
        "SELECT name, age, balance, RANK() OVER (PARTITION BY age ORDER BY balance DESC) as"
            + " age_rank FROM users LIMIT 100",
        "SELECT name, age, balance, RANK() OVER (PARTITION BY age ORDER BY balance DESC) as"
            + " age_rank FROM users LIMIT 100",
        "Window PARTITION BY",
        ITERATIONS);

    // --- UNION ALL ---
    benchQuery(
        sConn,
        lConn,
        "SELECT name, 'high' as category FROM users WHERE balance > 50000 UNION ALL SELECT name,"
            + " 'low' as category FROM users WHERE balance <= 50000 LIMIT 100",
        "SELECT name, 'high' as category FROM users WHERE balance > 50000 UNION ALL SELECT name,"
            + " 'low' as category FROM users WHERE balance <= 50000 LIMIT 100",
        "UNION ALL",
        ITERATIONS);

    // --- CASE expression ---
    benchQuery(
        sConn,
        lConn,
        "SELECT name, CASE WHEN balance > 75000 THEN 'platinum' WHEN balance > 50000 THEN 'gold'"
            + " WHEN balance > 25000 THEN 'silver' ELSE 'bronze' END as tier FROM users LIMIT 100",
        "SELECT name, CASE WHEN balance > 75000 THEN 'platinum' WHEN balance > 50000 THEN 'gold'"
            + " WHEN balance > 25000 THEN 'silver' ELSE 'bronze' END as tier FROM users LIMIT 100",
        "CASE expression",
        ITERATIONS);

    // --- Complex JOIN+GROUP+HAVING ---
    benchQuery(
        sConn,
        lConn,
        "SELECT u.name, COUNT(DISTINCT o.id) as orders, SUM(o.amount) as total FROM users u INNER"
            + " JOIN orders o ON u.id = o.user_id WHERE u.active = true AND o.status IN"
            + " ('completed', 'shipped') GROUP BY u.id, u.name HAVING COUNT(o.id) > 1 LIMIT 50",
        "SELECT u.name, COUNT(DISTINCT o.id) as orders, SUM(o.amount) as total FROM users u INNER"
            + " JOIN orders o ON u.id = o.user_id WHERE u.active = 1 AND o.status IN ('completed',"
            + " 'shipped') GROUP BY u.id, u.name HAVING COUNT(o.id) > 1 LIMIT 50",
        "Complex JOIN+GRP+HAVING",
        20);

    // --- Batch INSERT (100 rows in transaction) ---
    {
      int iters = ITERATIONS;
      int baseId = ROW_COUNT * 10;
      PreparedStatement sPs =
          sConn.prepareStatement(
              "INSERT INTO orders (id, user_id, amount, status, order_date) VALUES ($1, $2, $3, $4,"
                  + " $5)");
      PreparedStatement lPs =
          lConn.prepareStatement(
              "INSERT INTO orders (id, user_id, amount, status, order_date) VALUES (?, ?, ?, ?,"
                  + " ?)");

      long t0 = System.nanoTime();
      for (int it = 0; it < iters; it++) {
        sConn.setAutoCommit(false);
        for (int j = 0; j < 100; j++) {
          int id_ = baseId + it * 100 + j;
          sPs.setLong(1, id_);
          sPs.setLong(2, 1);
          sPs.setDouble(3, 100.0);
          sPs.setString(4, "pending");
          sPs.setString(5, "2024-02-01");
          sPs.addBatch();
        }
        sPs.executeBatch();
        sConn.commit();
        sConn.setAutoCommit(true);
      }
      double sUs = (System.nanoTime() - t0) / 1000.0 / iters;

      t0 = System.nanoTime();
      for (int it = 0; it < iters; it++) {
        lConn.setAutoCommit(false);
        for (int j = 0; j < 100; j++) {
          int id_ = baseId + iters * 100 + it * 100 + j;
          lPs.setLong(1, id_);
          lPs.setLong(2, 1);
          lPs.setDouble(3, 100.0);
          lPs.setString(4, "pending");
          lPs.setString(5, "2024-02-01");
          lPs.addBatch();
        }
        lPs.executeBatch();
        lConn.commit();
        lConn.setAutoCommit(true);
      }
      double lUs = (System.nanoTime() - t0) / 1000.0 / iters;

      printRow("Batch INSERT (100 rows)", sUs, lUs);
      sPs.close();
      lPs.close();
    }

    // ============================================================
    // BOTTLENECK HUNTERS
    // ============================================================
    printHeader("BOTTLENECK HUNTERS");

    benchQuery(
        sConn,
        lConn,
        "SELECT DISTINCT age FROM users",
        "SELECT DISTINCT age FROM users",
        "DISTINCT (no ORDER)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT DISTINCT age FROM users ORDER BY age",
        "SELECT DISTINCT age FROM users ORDER BY age",
        "DISTINCT + ORDER BY",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT COUNT(DISTINCT age) FROM users",
        "SELECT COUNT(DISTINCT age) FROM users",
        "COUNT DISTINCT",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users WHERE name LIKE 'User_1%' LIMIT 100",
        "SELECT * FROM users WHERE name LIKE 'User_1%' LIMIT 100",
        "LIKE prefix (User_1%)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users WHERE email LIKE '%50%' LIMIT 100",
        "SELECT * FROM users WHERE email LIKE '%50%' LIMIT 100",
        "LIKE contains (%50%)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users WHERE age = 25 OR age = 50 OR age = 75 LIMIT 100",
        "SELECT * FROM users WHERE age = 25 OR age = 50 OR age = 75 LIMIT 100",
        "OR conditions (3 vals)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users WHERE age IN (20, 25, 30, 35, 40, 45, 50) LIMIT 100",
        "SELECT * FROM users WHERE age IN (20, 25, 30, 35, 40, 45, 50) LIMIT 100",
        "IN list (7 values)",
        ITERATIONS);

    // --- NOT IN subquery ---
    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM orders WHERE status ="
            + " 'cancelled') LIMIT 100",
        "SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM orders WHERE status ="
            + " 'cancelled') LIMIT 100",
        "NOT IN subquery",
        10);

    // --- NOT EXISTS subquery ---
    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users u WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND"
            + " o.status = 'cancelled') LIMIT 100",
        "SELECT * FROM users u WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND"
            + " o.status = 'cancelled') LIMIT 100",
        "NOT EXISTS subquery",
        100);

    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users ORDER BY id LIMIT 100 OFFSET 5000",
        "SELECT * FROM users ORDER BY id LIMIT 100 OFFSET 5000",
        "OFFSET pagination (5000)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users ORDER BY age DESC, balance ASC, name LIMIT 100",
        "SELECT * FROM users ORDER BY age DESC, balance ASC, name LIMIT 100",
        "Multi-col ORDER BY (3)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT u1.name, u2.name, u1.age FROM users u1 INNER JOIN users u2 ON u1.age = u2.age AND"
            + " u1.id < u2.id LIMIT 100",
        "SELECT u1.name, u2.name, u1.age FROM users u1 INNER JOIN users u2 ON u1.age = u2.age AND"
            + " u1.id < u2.id LIMIT 100",
        "Self JOIN (same age)",
        100);

    benchQuery(
        sConn,
        lConn,
        "SELECT name, balance, ROW_NUMBER() OVER (ORDER BY balance DESC) as rn, RANK() OVER (ORDER"
            + " BY balance DESC) as rnk, LAG(balance) OVER (ORDER BY balance DESC) as prev_bal FROM"
            + " users LIMIT 100",
        "SELECT name, balance, ROW_NUMBER() OVER (ORDER BY balance DESC) as rn, RANK() OVER (ORDER"
            + " BY balance DESC) as rnk, LAG(balance) OVER (ORDER BY balance DESC) as prev_bal FROM"
            + " users LIMIT 100",
        "Multi window funcs (3)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE amount > (SELECT"
            + " AVG(amount) FROM orders)) LIMIT 100",
        "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE amount > (SELECT"
            + " AVG(amount) FROM orders)) LIMIT 100",
        "Nested subquery (3 lvl)",
        20);

    benchQuery(
        sConn,
        lConn,
        "SELECT COUNT(*), SUM(balance), AVG(balance), MIN(balance), MAX(balance), COUNT(DISTINCT"
            + " age) FROM users",
        "SELECT COUNT(*), SUM(balance), AVG(balance), MIN(balance), MAX(balance), COUNT(DISTINCT"
            + " age) FROM users",
        "Multi aggregates (6)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT name, COALESCE(balance, 0) as bal FROM users WHERE balance IS NOT NULL LIMIT 100",
        "SELECT name, COALESCE(balance, 0) as bal FROM users WHERE balance IS NOT NULL LIMIT 100",
        "COALESCE + IS NOT NULL",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users WHERE LENGTH(name) > 7 AND UPPER(name) LIKE 'USER_%' LIMIT 100",
        "SELECT * FROM users WHERE LENGTH(name) > 7 AND UPPER(name) LIKE 'USER_%' LIMIT 100",
        "Expr in WHERE (funcs)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT name, balance * 1.1 as new_bal, ROUND(balance / 1000, 2) as k_bal, ABS(balance -"
            + " 50000) as diff FROM users LIMIT 100",
        "SELECT name, balance * 1.1 as new_bal, ROUND(balance / 1000, 2) as k_bal, ABS(balance -"
            + " 50000) as diff FROM users LIMIT 100",
        "Math expressions",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT name || ' (' || email || ')' as full_info FROM users LIMIT 100",
        "SELECT name || ' (' || email || ')' as full_info FROM users LIMIT 100",
        "String concat (||)",
        ITERATIONS);

    // --- Large result (no LIMIT) ---
    benchQuery(
        sConn,
        lConn,
        "SELECT id, name, balance FROM users WHERE active = true",
        "SELECT id, name, balance FROM users WHERE active = 1",
        "Large result (no LIMIT)",
        20);

    benchQuery(
        sConn,
        lConn,
        "WITH young AS (SELECT * FROM users WHERE age < 30), rich AS (SELECT * FROM users WHERE"
            + " balance > 70000) SELECT y.name, r.name FROM young y INNER JOIN rich r ON y.id ="
            + " r.id LIMIT 50",
        "WITH young AS (SELECT * FROM users WHERE age < 30), rich AS (SELECT * FROM users WHERE"
            + " balance > 70000) SELECT y.name, r.name FROM young y INNER JOIN rich r ON y.id ="
            + " r.id LIMIT 50",
        "Multiple CTEs (2)",
        100);

    benchQuery(
        sConn,
        lConn,
        "SELECT u.name, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) as order_count FROM"
            + " users u LIMIT 100",
        "SELECT u.name, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) as order_count FROM"
            + " users u LIMIT 100",
        "Correlated in SELECT",
        100);

    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users WHERE balance BETWEEN 25000 AND 75000 LIMIT 100",
        "SELECT * FROM users WHERE balance BETWEEN 25000 AND 75000 LIMIT 100",
        "BETWEEN (non-indexed)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT age, active, COUNT(*), AVG(balance) FROM users GROUP BY age, active",
        "SELECT age, active, COUNT(*), AVG(balance) FROM users GROUP BY age, active",
        "GROUP BY (2 columns)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT u.name, o.status FROM users u CROSS JOIN (SELECT DISTINCT status FROM orders) o"
            + " LIMIT 100",
        "SELECT u.name, o.status FROM users u CROSS JOIN (SELECT DISTINCT status FROM orders) o"
            + " LIMIT 100",
        "CROSS JOIN (limited)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT t.age_group, COUNT(*) FROM (SELECT CASE WHEN age < 30 THEN 'young' WHEN age < 50"
            + " THEN 'middle' ELSE 'senior' END as age_group FROM users) t GROUP BY t.age_group",
        "SELECT t.age_group, COUNT(*) FROM (SELECT CASE WHEN age < 30 THEN 'young' WHEN age < 50"
            + " THEN 'middle' ELSE 'senior' END as age_group FROM users) t GROUP BY t.age_group",
        "Derived table (FROM sub)",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT name, balance, SUM(balance) OVER (ORDER BY balance ROWS BETWEEN 2 PRECEDING AND 2"
            + " FOLLOWING) as rolling_sum FROM users LIMIT 100",
        "SELECT name, balance, SUM(balance) OVER (ORDER BY balance ROWS BETWEEN 2 PRECEDING AND 2"
            + " FOLLOWING) as rolling_sum FROM users LIMIT 100",
        "Window ROWS frame",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT age FROM users GROUP BY age HAVING COUNT(*) > 100 AND AVG(balance) > 40000",
        "SELECT age FROM users GROUP BY age HAVING COUNT(*) > 100 AND AVG(balance) > 40000",
        "HAVING complex",
        ITERATIONS);

    benchQuery(
        sConn,
        lConn,
        "SELECT * FROM users WHERE balance > (SELECT AVG(amount) * 100 FROM orders) LIMIT 100",
        "SELECT * FROM users WHERE balance > (SELECT AVG(amount) * 100 FROM orders) LIMIT 100",
        "Compare with subquery",
        ITERATIONS);

    // ============================================================
    // Summary
    // ============================================================
    System.out.println();
    System.out.println("=".repeat(80));
    System.out.printf("SCORE: Stoolap %d wins  |  SQLite %d wins%n", stoolapWins, sqliteWins);
    System.out.println();
    System.out.println("NOTES:");
    System.out.println("- Both drivers use synchronous JDBC methods — fair comparison");
    System.out.println("- Stoolap: MVCC, parallel execution, columnar indexes");
    System.out.println("- SQLite: WAL mode, in-memory, JDBC driver (xerial)");
    System.out.println("- Ratio > 1x = Stoolap faster  |  * = SQLite faster");
    System.out.println("=".repeat(80));

    sStmt.close();
    lStmt.close();
    sConn.close();
    lConn.close();
  }

  /**
   * Benchmark a read-only query (same SQL or stoolap/sqlite variants). Uses prepared statements,
   * warmup, and drain to consume all rows.
   */
  static void benchQuery(
      Connection sConn, Connection lConn, String sSql, String lSql, String label, int iters)
      throws Exception {
    PreparedStatement sPs = sConn.prepareStatement(sSql);
    PreparedStatement lPs = lConn.prepareStatement(lSql);

    for (int i = 0; i < WARMUP; i++) {
      drain(sPs.executeQuery());
      drain(lPs.executeQuery());
    }

    long t0 = System.nanoTime();
    for (int i = 0; i < iters; i++) {
      drain(sPs.executeQuery());
    }
    double sUs = (System.nanoTime() - t0) / 1000.0 / iters;

    t0 = System.nanoTime();
    for (int i = 0; i < iters; i++) {
      drain(lPs.executeQuery());
    }
    double lUs = (System.nanoTime() - t0) / 1000.0 / iters;

    printRow(label, sUs, lUs);
    sPs.close();
    lPs.close();
  }
}
