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
package io.stoolap.jdbc;

import io.stoolap.StoolapDB;
import io.stoolap.StoolapException;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver for Stoolap.
 *
 * <p>JDBC URL format: {@code jdbc:stoolap:memory://} or {@code jdbc:stoolap:file:///path/to/db}
 *
 * <p>Usage:
 *
 * <pre>{@code
 * Connection conn = DriverManager.getConnection("jdbc:stoolap:memory://");
 * Statement stmt = conn.createStatement();
 * stmt.execute("CREATE TABLE t (id INTEGER, name TEXT)");
 * ResultSet rs = stmt.executeQuery("SELECT * FROM t");
 * }</pre>
 */
public class StoolapDriver implements Driver {

  private static final String URL_PREFIX = "jdbc:stoolap:";

  static {
    try {
      DriverManager.registerDriver(new StoolapDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) return null;
    String dsn = url.substring(URL_PREFIX.length());
    try {
      StoolapDB db = StoolapDB.open(dsn);
      return new StoolapConnection(db);
    } catch (StoolapException e) {
      throw new SQLException("Failed to connect: " + e.getMessage(), e);
    }
  }

  @Override
  public boolean acceptsURL(String url) {
    return url != null && url.startsWith(URL_PREFIX);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    return new DriverPropertyInfo[0];
  }

  @Override
  public int getMajorVersion() {
    return 0;
  }

  @Override
  public int getMinorVersion() {
    return 4;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException();
  }
}
