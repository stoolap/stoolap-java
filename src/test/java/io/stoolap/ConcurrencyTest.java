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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Handles used from several threads at once. Before 0.5.0 these crashed or hung the JVM: a handle
 * freed during a native call, or two threads in one transaction or cursor.
 */
class ConcurrencyTest {

  private static final int THREADS = 4;
  private ExecutorService pool;
  private StoolapDB db;

  @BeforeEach
  void setUp() throws Exception {
    pool = Executors.newFixedThreadPool(THREADS);
    db = StoolapDB.openInMemory();
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
    try (StoolapStmt ins = db.prepare("INSERT INTO t VALUES ($1, $2)");
        StoolapTx tx = db.begin()) {
      for (int i = 0; i < 5000; i++) tx.execute(ins, i, "name" + i);
      tx.commit();
    }
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    db.close();
  }

  /** A failure other than the "is closed" error a closed handle must raise. */
  private static void expectClosedOnly(Exception e, AtomicReference<Throwable> unexpected) {
    if (!(e instanceof StoolapException) || !e.getMessage().contains("closed")) {
      unexpected.compareAndSet(null, e);
    }
  }

  @Test
  void closingRacesUseOfAStatementAndADatabase() throws Exception {
    AtomicReference<Throwable> unexpected = new AtomicReference<>();
    for (int round = 0; round < 300; round++) {
      StoolapDB d = db.cloneHandle();
      StoolapStmt st = d.prepare("SELECT * FROM t WHERE id < $1");
      CountDownLatch go = new CountDownLatch(1);
      Future<?> user =
          pool.submit(
              () -> {
                try {
                  go.await();
                  for (int i = 0; i < 20; i++) {
                    st.query(100);
                    d.query("SELECT * FROM t WHERE id < 100");
                  }
                } catch (Exception e) {
                  expectClosedOnly(e, unexpected);
                }
                return null;
              });
      Future<?> closer =
          pool.submit(
              () -> {
                go.await();
                st.close();
                d.close();
                return null;
              });
      go.countDown();
      user.get();
      closer.get();
    }
    assertNull(unexpected.get());
  }

  @Test
  void closingRacesUseOfATransaction() throws Exception {
    AtomicReference<Throwable> unexpected = new AtomicReference<>();
    for (int round = 0; round < 300; round++) {
      StoolapTx tx = db.begin();
      CountDownLatch go = new CountDownLatch(1);
      Future<?> user =
          pool.submit(
              () -> {
                try {
                  go.await();
                  for (int i = 0; i < 20; i++) tx.query("SELECT * FROM t WHERE id < 100");
                } catch (StoolapException e) {
                  // "closed" or "already ended", both fine once close() ran
                }
                return null;
              });
      Future<?> closer =
          pool.submit(
              () -> {
                go.await();
                tx.close();
                return null;
              });
      go.countDown();
      user.get();
      closer.get();
    }
    assertNull(unexpected.get());
  }

  @Test
  void threadsSharingOneCursorReadEachRowOnce() throws Exception {
    for (int round = 0; round < 20; round++) {
      AtomicLong rows = new AtomicLong();
      try (StoolapCursor c = db.cursor("SELECT * FROM t")) {
        List<Future<?>> readers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
          readers.add(
              pool.submit(
                  () -> {
                    while (c.next() != null) rows.incrementAndGet();
                    return null;
                  }));
        }
        for (Future<?> f : readers) f.get();
      }
      assertEquals(5000, rows.get());
    }
  }

  @Test
  void threadsSharingOneTransaction() throws Exception {
    try (StoolapTx tx = db.begin()) {
      List<Future<?>> writers = new ArrayList<>();
      for (int t = 0; t < THREADS; t++) {
        long base = 100_000L * (t + 1);
        writers.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < 500; i++) {
                    tx.execute("INSERT INTO t VALUES ($1, 'x')", base + i);
                    tx.query("SELECT COUNT(*) FROM t WHERE id < 10");
                  }
                  return null;
                }));
      }
      for (Future<?> f : writers) f.get();
      tx.commit();
    }
    assertEquals(5000L + THREADS * 500L, db.query("SELECT COUNT(*) FROM t").rows().get(0)[0]);
  }

  @Test
  void threadsSharingOneJdbcConnectionDoNotHang() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(60),
        () -> {
          try (Connection c = DriverManager.getConnection("jdbc:stoolap:memory://")) {
            try (Statement s = c.createStatement()) {
              s.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
            }
            c.setAutoCommit(false);
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
              long base = 100_000L * (t + 1);
              workers.add(
                  pool.submit(
                      () -> {
                        try (PreparedStatement ps =
                                c.prepareStatement("INSERT INTO t VALUES (?, ?)");
                            Statement s = c.createStatement()) {
                          for (int i = 0; i < 1000; i++) {
                            ps.setLong(1, base + i);
                            ps.setString(2, "x");
                            ps.executeUpdate();
                            try (ResultSet rs = s.executeQuery("SELECT * FROM t WHERE id < 50")) {
                              while (rs.next()) rs.getLong(1);
                            }
                            if (i % 100 == 99) c.commit();
                          }
                        }
                        return null;
                      }));
            }
            for (Future<?> f : workers) f.get();
            c.commit();
            c.setAutoCommit(true);
            try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM t")) {
              rs.next();
              assertEquals(THREADS * 1000L, rs.getLong(1));
            }
          }
        });
  }

  @Test
  void anUnclosedHandleIsFreedOnceUnreachable() throws Exception {
    for (int i = 0; i < 2000; i++) {
      db.prepare("SELECT * FROM t WHERE id = $1").query(i); // never closed
      db.cursor("SELECT * FROM t"); // never read or closed
    }
    System.gc();
    assertEquals(5000L, db.query("SELECT COUNT(*) FROM t").rows().get(0)[0]);
  }
}
