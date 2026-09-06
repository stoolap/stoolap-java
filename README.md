# Stoolap Java Driver

High-performance Java driver for [Stoolap](https://github.com/stoolap/stoolap), an embedded SQL database engine written in Rust.

The driver uses **JNI** to call the Rust API directly (no C FFI layer), with a JDBC implementation on top for ecosystem compatibility. Query results are encoded entirely in Rust and returned as a packed binary buffer, eliminating per-row JNI crossings.

---

## Why this driver is fast

| Technique | Purpose |
|---|---|
| **Direct Rust API** via jni-rs | No C FFI wrapper layer — `stoolap::api::Database` is called directly |
| **Cached plans** | `db.prepare(sql)` stores a `CachedPlanRef`; execute/query skip parsing and cache lookup entirely |
| **Binary params** | Java encodes parameters to `byte[]` with `ParamEncoder`, Rust decodes with zero JNI reflection (no `is_instance_of` calls) |
| **Bulk row fetch** | Entire result set encoded in Rust to a single `byte[]`, decoded in Java with `BulkDecoder` |
| **Batch in tx** | `executeBatch()` auto-wraps in a transaction and calls `tx.execute_prepared(ast, params)` using the cached AST — zero SQL re-parsing per row |

A benchmark suite matching the Python `benchmark.py` is included at
[`src/test/java/io/stoolap/Benchmark.java`](src/test/java/io/stoolap/Benchmark.java) and compares
against SQLite JDBC (xerial) across 50+ core, advanced, and bottleneck queries. See
[Benchmark](#benchmark) below for how to run it.

---

## Requirements

- **Java 17+** (tested on Java 17, 21, 25)
- **Rust toolchain** (only required if building the native library from source)
- **macOS (aarch64/x86_64), Linux (x86_64/aarch64), or Windows (x86_64)**

---

## Quick start

### 1. Build the native library

```bash
cd stoolap-java/jni
cargo build --release
```

This produces `jni/target/release/libstoolap_jni.{dylib,so,dll}` depending on your platform.

### 2. Build the Java artifact

```bash
cd stoolap-java
mvn package
```

### 3. Run your code

Either set an environment variable pointing at the native library:

```bash
export STOOLAP_LIB=/path/to/stoolap-java/jni/target/release/libstoolap_jni.dylib
```

Or pass it on the command line:

```bash
java -Djava.library.path=/path/to/stoolap-java/jni/target/release \
     -cp stoolap-java-0.4.1.jar:your-app.jar \
     your.Main
```

---

## Usage

### Core API (recommended for max performance)

```java
import io.stoolap.StoolapDB;
import io.stoolap.StoolapStmt;
import io.stoolap.StoolapTx;
import io.stoolap.internal.BulkDecoder;

try (StoolapDB db = StoolapDB.openInMemory()) {
    db.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
    db.execute("INSERT INTO users VALUES ($1, $2, $3)", 1L, "Alice", 30L);
    db.execute("INSERT INTO users VALUES ($1, $2, $3)", 2L, "Bob", 25L);

    BulkDecoder.Result result = db.query("SELECT id, name, age FROM users ORDER BY id");
    for (Object[] row : result.rows()) {
        long id    = (Long) row[0];
        String name = (String) row[1];
        long age   = (Long) row[2];
        System.out.println(id + " " + name + " " + age);
    }
}
```

### Prepared statements

```java
try (StoolapDB db = StoolapDB.openInMemory()) {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");

    try (StoolapStmt stmt = db.prepare("INSERT INTO t VALUES ($1, $2)")) {
        stmt.execute(1L, "Alice");
        stmt.execute(2L, "Bob");
        stmt.execute(3L, "Charlie");
    }

    try (StoolapStmt sel = db.prepare("SELECT name FROM t WHERE id = $1")) {
        BulkDecoder.Result r = sel.query(2L);
        System.out.println(r.rows().get(0)[0]); // "Bob"
    }
}
```

### Transactions

```java
try (StoolapDB db = StoolapDB.openInMemory()) {
    db.execute("CREATE TABLE accounts (id INTEGER, balance INTEGER)");
    db.execute("INSERT INTO accounts VALUES (1, 100)");
    db.execute("INSERT INTO accounts VALUES (2, 0)");

    try (StoolapTx tx = db.begin()) {
        tx.execute("UPDATE accounts SET balance = balance - 50 WHERE id = 1");
        tx.execute("UPDATE accounts SET balance = balance + 50 WHERE id = 2");
        tx.commit();
    }
}
```

Snapshot isolation:

```java
try (StoolapTx tx = db.begin(true /* snapshot */)) {
    BulkDecoder.Result snapshot = tx.query("SELECT * FROM t");
    // Sees a consistent view even if other connections write.
    tx.commit();
}
```

### File-based databases

```java
// Simple path
try (StoolapDB db = StoolapDB.open("file:///var/data/mydb")) { ... }

// With DSN options
String dsn = "file:///var/data/mydb"
           + "?sync_mode=full"
           + "&checkpoint_interval=60"
           + "&wal_compression=on"
           + "&volume_compression=on";
try (StoolapDB db = StoolapDB.open(dsn)) { ... }
```

Supported DSN options (forwarded to the Rust engine):

| Option | Values | Meaning |
|---|---|---|
| `sync_mode` | `none`, `normal`, `full` | fsync frequency |
| `checkpoint_interval` | seconds | Background checkpoint period |
| `compact_threshold` | integer | Compaction threshold |
| `checkpoint_on_close` | `on`/`off` | Checkpoint before closing |
| `wal_compression` | `on`/`off` | Compress WAL |
| `volume_compression` | `on`/`off` | Compress volume files |

### Multi-threaded use

A single `StoolapDB` handle must NOT be used concurrently from multiple threads. Use `cloneHandle()` to create a per-thread handle that shares the underlying engine:

```java
StoolapDB main = StoolapDB.openInMemory();

// Per worker thread
Runnable worker = () -> {
    try (StoolapDB local = main.cloneHandle()) {
        local.query("SELECT ...");
    } catch (Exception e) {
        // ...
    }
};
```

---

## JDBC driver

Standard `java.sql` interface on top of the core API.

### Registration

The driver auto-registers via `META-INF/services/java.sql.Driver`. Just reference the URL:

```java
import java.sql.*;

try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
    try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
        stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice')");

        try (ResultSet rs = stmt.executeQuery("SELECT id, name FROM users")) {
            while (rs.next()) {
                System.out.println(rs.getInt("id") + " " + rs.getString("name"));
            }
        }
    }
}
```

### JDBC URL formats

| URL | Description |
|---|---|
| `jdbc:stoolap:memory://` | In-memory database (isolated) |
| `jdbc:stoolap:file:///path/to/db` | File-based |
| `jdbc:stoolap:file:///path/to/db?sync_mode=full` | File-based with DSN options |

### PreparedStatement + batch

```java
try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
    conn.createStatement().execute("CREATE TABLE t (id INTEGER, name TEXT)");

    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES ($1, $2)")) {
        for (int i = 0; i < 1000; i++) {
            ps.setLong(1, i);
            ps.setString(2, "item_" + i);
            ps.addBatch();
        }
        ps.executeBatch();
    }
}
```

### Transaction control

```java
try (Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://")) {
    conn.setAutoCommit(false);
    try (Statement stmt = conn.createStatement()) {
        stmt.executeUpdate("INSERT INTO t VALUES (1, 'a')");
        stmt.executeUpdate("INSERT INTO t VALUES (2, 'b')");
        conn.commit();
    } catch (SQLException e) {
        conn.rollback();
        throw e;
    } finally {
        conn.setAutoCommit(true);
    }
}
```

### Connection pool integration

`StoolapConnection` maps to a cloned `StoolapDB` handle, making it safe to use with HikariCP, DBCP, or other JDBC pools.

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:stoolap:file:///var/data/mydb");
config.setMaximumPoolSize(10);
HikariDataSource ds = new HikariDataSource(config);
```

---

## Value type mapping

| SQL type | Java type (read) | Java type (write) |
|---|---|---|
| `INTEGER` | `Long` | `Long`, `Integer`, `Short`, `Byte` |
| `FLOAT` | `Double` | `Double`, `Float`, `BigDecimal` (as double) |
| `TEXT` | `String` | `String` |
| `BOOLEAN` | `Boolean` | `Boolean` |
| `TIMESTAMP` | `java.time.Instant` | `java.sql.Timestamp`, `java.time.Instant` |
| `JSON` | `String` | `String` |
| `BLOB` | `byte[]` | `byte[]` |
| `NULL` | `null` | `null` |

Numeric aggregate results (`SUM`, `AVG`, `MIN`, `MAX` over integer columns) may be returned as `Double` depending on the engine's promotion rules. Use `((Number) value).longValue()` / `.doubleValue()` when reading aggregate output.

---

## Architecture

```
+------------------------------------------------------+
|              Your Java Application                   |
+------------------------------------------------------+
|  java.sql.*  (JDBC)       |  io.stoolap.*  (core)   |
|  +-- StoolapDriver        |  +-- StoolapDB           |
|  +-- StoolapConnection    |  +-- StoolapStmt         |
|  +-- StoolapStatement     |  +-- StoolapTx           |
|  +-- StoolapPreparedStmt  |                          |
|  +-- StoolapResultSet     |                          |
+------------------------------------------------------+
|  io.stoolap.internal                                |
|  +-- NativeBridge  (static native methods)           |
|  +-- ParamEncoder  (Java -> byte[])                  |
|  +-- BulkDecoder   (byte[] -> Object[])              |
+------------------------------------------------------+
           |          JNI (jni-rs)         |
           v                                v
+------------------------------------------------------+
|         stoolap-jni  (jni/ Rust crate)               |
|  +-- Wraps stoolap::api::{Database, Statement, Tx}   |
|  +-- decode_binary_params / decode_binary_batch      |
|  +-- encode_rows   (Rust -> byte[])                  |
+------------------------------------------------------+
                          |
                          v
+------------------------------------------------------+
|                 stoolap crate (Rust)                 |
|  MVCC, columnar indexes, volume storage, WAL         |
+------------------------------------------------------+
```

### Wire format (Java <-> Rust)

**Parameters** (Java -> Rust, via `ParamEncoder`):

```
[param_count: u32 LE]
[for each param: type_tag:u8 + payload]

type_tag:
  0 NULL      (no payload)
  1 INTEGER   i64 LE (8 bytes)
  2 FLOAT     f64 LE (8 bytes)
  3 TEXT      len:u32 LE + UTF-8 bytes
  4 BOOLEAN   u8 (0/1)
```

Batch format is the same with an extra `[row_count:u32][param_count:u32]` header.

**Rows** (Rust -> Java, via `BulkDecoder`) uses the same layout but includes column names and timestamps/JSON/blob types (5/6/7).

---

## Project layout

```
stoolap-java/
|-- pom.xml                      (Maven build)
|-- jni/
|   |-- Cargo.toml               (Rust crate, depends on ../../stoolap)
|   `-- src/lib.rs               (JNI bindings)
`-- src/
    |-- main/java/io/stoolap/
    |   |-- StoolapDB.java
    |   |-- StoolapStmt.java
    |   |-- StoolapTx.java
    |   |-- StoolapException.java
    |   |-- internal/
    |   |   |-- NativeBridge.java    (static native methods + library loader)
    |   |   |-- ParamEncoder.java    (Java -> byte[])
    |   |   `-- BulkDecoder.java     (byte[] -> Object[])
    |   `-- jdbc/
    |       |-- StoolapDriver.java
    |       |-- StoolapConnection.java
    |       |-- StoolapStatement.java
    |       |-- StoolapPreparedStatement.java
    |       |-- StoolapResultSet.java
    |       |-- StoolapResultSetMetaData.java
    |       `-- StoolapDatabaseMetaData.java
    |-- main/resources/META-INF/services/
    |   `-- java.sql.Driver
    `-- test/java/io/stoolap/
        |-- StoolapDBTest.java       (57 tests - core API)
        |-- JdbcTest.java            (47 tests - JDBC driver)
        |-- PersistenceTest.java     (11 tests - file DB open/reopen)
        `-- Benchmark.java           (vs SQLite JDBC)
```

---

## Tests

```bash
export STOOLAP_LIB=jni/target/release/libstoolap_jni.dylib
mvn test
```

115 tests total:

| Test file | Count | Covers |
|---|---|---|
| `StoolapDBTest` | 57 | Core API: open, execute, query, prepare, begin, clone, error handling, all SQL features |
| `JdbcTest` | 47 | JDBC driver: Connection, Statement, PreparedStatement, ResultSet, metadata, batches |
| `PersistenceTest` | 11 | File DB: write → close → reopen → verify (matches Python test_persistence.py) |

### Benchmark

```bash
java -Djava.library.path=jni/target/release \
     -cp "target/classes:target/test-classes:$(mvn dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/dev/stdout)" \
     io.stoolap.Benchmark
```

---

## Building from source

### Native library only

```bash
cd jni
cargo build --release
# Output: jni/target/release/libstoolap_jni.{dylib,so,dll}
```

### Full project

```bash
mvn clean package
# Output: target/stoolap-java-0.4.1.jar
```

### Shipping prebuilt libraries in the JAR

Place compiled native libraries at:

```
src/main/resources/native/
  darwin-aarch64/libstoolap_jni.dylib
  darwin-x86_64/libstoolap_jni.dylib
  linux-x86_64/libstoolap_jni.so
  linux-aarch64/libstoolap_jni.so
  windows-x86_64/stoolap_jni.dll
```

`NativeBridge` extracts the matching binary to a temp file at startup and loads it.

---

## Troubleshooting

### `UnsatisfiedLinkError: Cannot find stoolap_jni native library`

The driver searches in this order:

1. `STOOLAP_LIB` environment variable (exact path)
2. JAR resource at `/native/{os}-{arch}/libstoolap_jni.{ext}`
3. System library path (`java.library.path`)

Set `STOOLAP_LIB` to the absolute path of the built library:

```bash
export STOOLAP_LIB=$(pwd)/jni/target/release/libstoolap_jni.dylib
```

### `WARNING: java.lang.System::load has been called`

On Java 25+ you'll see a warning about restricted native access. To suppress:

```bash
java --enable-native-access=ALL-UNNAMED -cp ... YourMain
```

Or add to the JAR manifest:

```
Enable-Native-Access: ALL-UNNAMED
```

### Version skew between Java driver and Rust engine

The `jni/Cargo.toml` has `stoolap = { path = "../../stoolap" }`. Make sure the path points to a compatible stoolap crate version. Rebuild the native library after updating stoolap.

---

## License

Apache License 2.0. See [LICENSE](LICENSE) (or the license headers on each file).

---

## Related projects

- [stoolap](https://github.com/stoolap/stoolap) - The underlying Rust engine
- [stoolap-python](https://github.com/stoolap/stoolap-python) - Python driver (PyO3)
- [stoolap-go](https://github.com/stoolap/stoolap-go) - Go driver (CGO)
- [stoolap-node](https://github.com/stoolap/stoolap-node) - Node.js driver (N-API)
