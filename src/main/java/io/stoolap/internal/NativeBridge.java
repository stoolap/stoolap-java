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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;

/**
 * JNI bridge to the Rust stoolap engine.
 *
 * <p>Calls the Rust API directly (no C FFI layer). Each handle is a Rust {@code Box<T>} stored as a
 * Java {@code long}. Query results are encoded entirely in Rust and returned as {@code byte[]} in
 * the BulkDecoder format.
 *
 * <p>Library search order:
 *
 * <ol>
 *   <li>{@code STOOLAP_LIB} env var (exact path to .dylib/.so/.dll)
 *   <li>Bundled in JAR at {@code /native/{os}-{arch}/}
 *   <li>System library path ({@code java.library.path})
 * </ol>
 */
public final class NativeBridge {

  static {
    loadLibrary();
  }

  private NativeBridge() {}

  // Every handle argument must be live for the whole call; NativeHandle guarantees that.
  // A params argument may be null for no parameters. A *QueryCursor call returns
  // [cursor_handle:i64][first chunk]; see ChunkReader.

  // ---- Database lifecycle ----

  public static native long open(String dsn) throws SQLException;

  public static native long openInMemory() throws SQLException;

  public static native void closeDb(long ptr);

  public static native long cloneDb(long ptr) throws SQLException;

  public static native String version();

  /** {@link #placement}: run inside the open transaction (DML, savepoints). */
  public static final int IN_TX = 0;

  /** {@link #placement}: commit the open transaction, then run outside it (DDL). */
  public static final int COMMIT_FIRST = 1;

  /** {@link #placement}: run outside, leaving the transaction open (SHOW, DESCRIBE, EXPLAIN). */
  public static final int OUTSIDE = 2;

  /** {@link #placement}: BEGIN or COMMIT, which commit the open transaction. */
  public static final int COMMIT = 3;

  /** {@link #placement}: ROLLBACK, which rolls the open transaction back. */
  public static final int ROLLBACK = 4;

  /** Where a manual-commit connection runs {@code sql}: one of the constants above. */
  public static native int placement(String sql) throws SQLException;

  // ---- Database: execute and query ----

  /** A {@code timeoutMs} above zero bounds the statement's run time (positional params only). */
  public static native long execParams(long dbPtr, String sql, byte[] params, int timeoutMs)
      throws SQLException;

  public static native byte[] queryParams(long dbPtr, String sql, byte[] params)
      throws SQLException;

  public static native byte[] queryCursor(
      long dbPtr, String sql, byte[] params, int maxRows, int timeoutMs) throws SQLException;

  // ---- Prepared statements ----

  public static native long prepare(long dbPtr, String sql) throws SQLException;

  public static native void stmtClose(long stmtPtr);

  public static native long stmtExec(long stmtPtr, byte[] params) throws SQLException;

  public static native byte[] stmtQuery(long stmtPtr, byte[] params) throws SQLException;

  public static native byte[] stmtQueryCursor(long stmtPtr, byte[] params, int maxRows)
      throws SQLException;

  /** Runs the batch in a transaction of its own on the database: all rows commit, or none. */
  public static native long[] stmtExecBatch(long dbPtr, long stmtPtr, byte[] batch)
      throws SQLException;

  // ---- Transactions ----

  public static native long begin(long dbPtr, int isolation) throws SQLException;

  public static native boolean txCommit(long txPtr) throws SQLException;

  public static native boolean txRollback(long txPtr) throws SQLException;

  public static native void txClose(long txPtr);

  public static native long txExecParams(long txPtr, String sql, byte[] params) throws SQLException;

  public static native byte[] txQueryParams(long txPtr, String sql, byte[] params)
      throws SQLException;

  public static native byte[] txQueryCursor(long txPtr, String sql, byte[] params, int maxRows)
      throws SQLException;

  public static native long txStmtExec(long txPtr, long stmtPtr, byte[] params) throws SQLException;

  public static native byte[] txStmtQuery(long txPtr, long stmtPtr, byte[] params)
      throws SQLException;

  public static native byte[] txStmtQueryCursor(
      long txPtr, long stmtPtr, byte[] params, int maxRows) throws SQLException;

  public static native long[] txStmtExecBatch(long txPtr, long stmtPtr, byte[] batch)
      throws SQLException;

  // ---- Cursors (chunked results) ----

  /** Up to {@code maxRows} more rows; a chunk with fewer rows than asked for is the last. */
  public static native byte[] cursorFetch(long cursorPtr, int maxRows) throws SQLException;

  public static native void cursorClose(long cursorPtr);

  // ---- Library loading ----

  private static void loadLibrary() {
    // 1. STOOLAP_LIB env var
    String envPath = System.getenv("STOOLAP_LIB");
    if (envPath != null && !envPath.isEmpty()) {
      System.load(envPath);
      return;
    }

    // 2. JAR resource
    String resourcePath = "/native/" + osDirName() + "-" + archDirName() + "/" + libFileName();
    try (InputStream in = NativeBridge.class.getResourceAsStream(resourcePath)) {
      if (in != null) {
        Path tmpDir = Files.createTempDirectory("stoolap-jni");
        tmpDir.toFile().deleteOnExit();
        Path tmpLib = tmpDir.resolve(libFileName());
        tmpLib.toFile().deleteOnExit();
        Files.copy(in, tmpLib, StandardCopyOption.REPLACE_EXISTING);
        System.load(tmpLib.toString());
        return;
      }
    } catch (IOException e) {
      // fall through
    }

    // 3. System path
    try {
      System.loadLibrary("stoolap_jni");
      return;
    } catch (UnsatisfiedLinkError e) {
      // fall through
    }

    throw new UnsatisfiedLinkError(
        "Cannot find stoolap_jni native library. Options:\n"
            + "  1. Set STOOLAP_LIB=/path/to/libstoolap_jni."
            + libExtension()
            + "\n"
            + "  2. Place "
            + libFileName()
            + " in JAR resources at "
            + resourcePath
            + "\n"
            + "  3. Add library directory to java.library.path\n"
            + "  Build: cd stoolap-java/jni && cargo build --release");
  }

  private static String libFileName() {
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac") || os.contains("darwin")) return "libstoolap_jni.dylib";
    if (os.contains("win")) return "stoolap_jni.dll";
    return "libstoolap_jni.so";
  }

  private static String libExtension() {
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac") || os.contains("darwin")) return "dylib";
    if (os.contains("win")) return "dll";
    return "so";
  }

  private static String osDirName() {
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac") || os.contains("darwin")) return "darwin";
    if (os.contains("win")) return "windows";
    return "linux";
  }

  private static String archDirName() {
    String arch = System.getProperty("os.arch", "").toLowerCase();
    if (arch.equals("aarch64") || arch.equals("arm64")) return "aarch64";
    return "x86_64";
  }
}
