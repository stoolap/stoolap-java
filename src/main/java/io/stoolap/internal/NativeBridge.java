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

  // ---- Database lifecycle ----

  public static native long open(String dsn) throws SQLException;

  public static native long openInMemory() throws SQLException;

  public static native void closeDb(long ptr);

  public static native long cloneDb(long ptr);

  public static native String version();

  // ---- Execute (DDL/DML) ----

  public static native long exec(long dbPtr, String sql) throws SQLException;

  public static native long execParams(long dbPtr, String sql, byte[] binParams)
      throws SQLException;

  // ---- Query (returns packed byte[] via BulkDecoder format) ----

  public static native byte[] query(long dbPtr, String sql) throws SQLException;

  public static native byte[] queryParams(long dbPtr, String sql, byte[] binParams)
      throws SQLException;

  // ---- Prepared statements ----

  public static native long prepare(long dbPtr, String sql) throws SQLException;

  public static native void stmtClose(long stmtPtr);

  public static native long stmtExec(long stmtPtr, byte[] binParams) throws SQLException;

  public static native byte[] stmtQuery(long stmtPtr, byte[] binParams) throws SQLException;

  public static native long[] stmtExecBatch(long stmtPtr, byte[] binBatch) throws SQLException;

  // ---- Transactions ----

  public static native long begin(long dbPtr, int isolation) throws SQLException;

  public static native boolean txCommit(long txPtr) throws SQLException;

  public static native boolean txRollback(long txPtr) throws SQLException;

  public static native void txClose(long txPtr);

  public static native long txExec(long txPtr, String sql) throws SQLException;

  public static native long txExecParams(long txPtr, String sql, byte[] binParams)
      throws SQLException;

  public static native byte[] txQuery(long txPtr, String sql) throws SQLException;

  public static native byte[] txQueryParams(long txPtr, String sql, byte[] binParams)
      throws SQLException;

  public static native long txStmtExec(long txPtr, long stmtPtr, byte[] binParams)
      throws SQLException;

  public static native byte[] txStmtQuery(long txPtr, long stmtPtr, byte[] binParams)
      throws SQLException;

  public static native long[] txStmtExecBatch(long txPtr, long stmtPtr, byte[] binBatch)
      throws SQLException;

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
