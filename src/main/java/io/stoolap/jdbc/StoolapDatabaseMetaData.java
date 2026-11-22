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
import io.stoolap.internal.BulkDecoder;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DatabaseMetaData for Stoolap, read from SHOW TABLES, SHOW VIEWS, DESCRIBE and SHOW INDEXES. */
public class StoolapDatabaseMetaData implements DatabaseMetaData {

  private final StoolapConnection conn;

  StoolapDatabaseMetaData(StoolapConnection conn) {
    this.conn = conn;
  }

  @Override
  public String getDatabaseProductName() throws SQLException {
    return "Stoolap";
  }

  @Override
  public String getDatabaseProductVersion() throws SQLException {
    return StoolapDB.version();
  }

  @Override
  public String getDriverName() throws SQLException {
    return "Stoolap JDBC Driver";
  }

  @Override
  public String getDriverVersion() throws SQLException {
    return "0.5.1";
  }

  @Override
  public int getDriverMajorVersion() {
    return 0;
  }

  @Override
  public int getDriverMinorVersion() {
    return 5;
  }

  @Override
  public int getDatabaseMajorVersion() throws SQLException {
    return 0;
  }

  @Override
  public int getDatabaseMinorVersion() throws SQLException {
    return 5;
  }

  @Override
  public int getJDBCMajorVersion() throws SQLException {
    return 4;
  }

  @Override
  public int getJDBCMinorVersion() throws SQLException {
    return 3;
  }

  @Override
  public String getURL() throws SQLException {
    return null;
  }

  @Override
  public String getUserName() throws SQLException {
    return "";
  }

  @Override
  public boolean isReadOnly() throws SQLException {
    return false;
  }

  @Override
  public Connection getConnection() throws SQLException {
    return conn;
  }

  @Override
  public boolean supportsBatchUpdates() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsTransactions() throws SQLException {
    return true;
  }

  @Override
  public int getDefaultTransactionIsolation() throws SQLException {
    return Connection.TRANSACTION_READ_COMMITTED;
  }

  @Override
  public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
    return level == Connection.TRANSACTION_READ_COMMITTED
        || level == Connection.TRANSACTION_REPEATABLE_READ;
  }

  @Override
  public String getSQLKeywords() throws SQLException {
    return "";
  }

  @Override
  public String getIdentifierQuoteString() throws SQLException {
    return "\"";
  }

  @Override
  public String getCatalogSeparator() throws SQLException {
    return ".";
  }

  @Override
  public String getCatalogTerm() throws SQLException {
    return "catalog";
  }

  @Override
  public String getSchemaTerm() throws SQLException {
    return "schema";
  }

  @Override
  public String getProcedureTerm() throws SQLException {
    return "procedure";
  }

  // --- Boolean capabilities ---
  @Override
  public boolean allProceduresAreCallable() throws SQLException {
    return false;
  }

  @Override
  public boolean allTablesAreSelectable() throws SQLException {
    return true;
  }

  @Override
  public boolean nullsAreSortedHigh() throws SQLException {
    return false;
  }

  @Override
  public boolean nullsAreSortedLow() throws SQLException {
    return true;
  }

  @Override
  public boolean nullsAreSortedAtStart() throws SQLException {
    return false;
  }

  @Override
  public boolean nullsAreSortedAtEnd() throws SQLException {
    return false;
  }

  @Override
  public boolean usesLocalFiles() throws SQLException {
    return true;
  }

  @Override
  public boolean usesLocalFilePerTable() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsMixedCaseIdentifiers() throws SQLException {
    return true;
  }

  @Override
  public boolean storesUpperCaseIdentifiers() throws SQLException {
    return false;
  }

  @Override
  public boolean storesLowerCaseIdentifiers() throws SQLException {
    return false;
  }

  @Override
  public boolean storesMixedCaseIdentifiers() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
    return true;
  }

  @Override
  public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
    return false;
  }

  @Override
  public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
    return false;
  }

  @Override
  public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsAlterTableWithAddColumn() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsAlterTableWithDropColumn() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsColumnAliasing() throws SQLException {
    return true;
  }

  @Override
  public boolean nullPlusNonNullIsNull() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsConvert() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsConvert(int fromType, int toType) throws SQLException {
    return false;
  }

  @Override
  public boolean supportsTableCorrelationNames() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsDifferentTableCorrelationNames() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsExpressionsInOrderBy() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsOrderByUnrelated() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsGroupBy() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsGroupByUnrelated() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsGroupByBeyondSelect() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsLikeEscapeClause() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsMultipleResultSets() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsMultipleTransactions() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsNonNullableColumns() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsMinimumSQLGrammar() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsCoreSQLGrammar() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsExtendedSQLGrammar() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsANSI92EntryLevelSQL() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsANSI92IntermediateSQL() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsANSI92FullSQL() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsIntegrityEnhancementFacility() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsOuterJoins() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsFullOuterJoins() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsLimitedOuterJoins() throws SQLException {
    return true;
  }

  @Override
  public boolean isCatalogAtStart() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInComparisons() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInExists() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInIns() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInQuantifieds() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsCorrelatedSubqueries() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsUnion() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsUnionAll() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
    return true;
  }

  @Override
  public int getMaxBinaryLiteralLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxCharLiteralLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnsInGroupBy() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnsInIndex() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnsInOrderBy() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnsInSelect() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnsInTable() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxConnections() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxCursorNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxIndexLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxSchemaNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxProcedureNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxCatalogNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxRowSize() throws SQLException {
    return 0;
  }

  @Override
  public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
    return false;
  }

  @Override
  public int getMaxStatementLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxStatements() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxTableNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxTablesInSelect() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxUserNameLength() throws SQLException {
    return 0;
  }

  @Override
  public boolean supportsSchemasInDataManipulation() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsSchemasInProcedureCalls() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsSchemasInTableDefinitions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsSchemasInIndexDefinitions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsCatalogsInDataManipulation() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsCatalogsInProcedureCalls() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsCatalogsInTableDefinitions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsSelectForUpdate() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsStoredProcedures() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsPositionedDelete() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsPositionedUpdate() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsResultSetType(int type) throws SQLException {
    return type == ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
    return concurrency == ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public boolean ownUpdatesAreVisible(int type) throws SQLException {
    return false;
  }

  @Override
  public boolean ownDeletesAreVisible(int type) throws SQLException {
    return false;
  }

  @Override
  public boolean ownInsertsAreVisible(int type) throws SQLException {
    return false;
  }

  @Override
  public boolean othersUpdatesAreVisible(int type) throws SQLException {
    return false;
  }

  @Override
  public boolean othersDeletesAreVisible(int type) throws SQLException {
    return false;
  }

  @Override
  public boolean othersInsertsAreVisible(int type) throws SQLException {
    return false;
  }

  @Override
  public boolean updatesAreDetected(int type) throws SQLException {
    return false;
  }

  @Override
  public boolean deletesAreDetected(int type) throws SQLException {
    return false;
  }

  @Override
  public boolean insertsAreDetected(int type) throws SQLException {
    return false;
  }

  @Override
  public boolean supportsResultSetHoldability(int holdability) throws SQLException {
    return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public int getSQLStateType() throws SQLException {
    return sqlStateSQL;
  }

  @Override
  public boolean locatorsUpdateCopy() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsStatementPooling() throws SQLException {
    return false;
  }

  @Override
  public RowIdLifetime getRowIdLifetime() throws SQLException {
    return RowIdLifetime.ROWID_UNSUPPORTED;
  }

  @Override
  public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
    return false;
  }

  @Override
  public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
    return false;
  }

  @Override
  public boolean generatedKeyAlwaysReturned() throws SQLException {
    return false;
  }

  @Override
  public long getMaxLogicalLobSize() throws SQLException {
    return 0;
  }

  @Override
  public boolean supportsRefCursors() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsSharding() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
    return true;
  }

  @Override
  public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
    return true;
  }

  @Override
  public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsSavepoints() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsNamedParameters() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsMultipleOpenResults() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsGetGeneratedKeys() throws SQLException {
    return true;
  }

  @Override
  public String getNumericFunctions() throws SQLException {
    return "";
  }

  @Override
  public String getStringFunctions() throws SQLException {
    return "";
  }

  @Override
  public String getSystemFunctions() throws SQLException {
    return "";
  }

  @Override
  public String getTimeDateFunctions() throws SQLException {
    return "";
  }

  @Override
  public String getSearchStringEscape() throws SQLException {
    return "\\";
  }

  @Override
  public String getExtraNameCharacters() throws SQLException {
    return "";
  }

  // --- ResultSet-returning methods (stubs) ---
  // ---- Catalog lookups ----
  //
  // Stoolap has no catalogs or schemas: TABLE_CAT and TABLE_SCHEM are null, and a catalog or
  // schema pattern other than null, "" or "%" matches nothing. Name patterns are JDBC LIKE
  // patterns (% and _, escaped with \), matched without regard to case as the engine does.
  // Lookups run on the database handle, so they never commit or join an open transaction.

  /** A double-quoted SQL identifier. */
  static String quote(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  /** An identifier as written in SQL, without its quotes. */
  static String unquote(String name) {
    if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
      return name.substring(1, name.length() - 1).replace("\"\"", "\"");
    }
    return name;
  }

  private static BulkDecoder.Result run(StoolapConnection conn, String sql) throws SQLException {
    conn.checkClosed();
    return conn.getDb().query(sql);
  }

  /** DESCRIBE rows of a table: Field, Type, Null, Key, Default, Extra. */
  static List<Object[]> describe(StoolapConnection conn, String table) throws SQLException {
    return run(conn, "DESCRIBE " + quote(table)).rows();
  }

  private static boolean noSchema(String pattern) {
    return pattern == null || pattern.isEmpty() || pattern.equals("%");
  }

  /** A JDBC LIKE pattern as a case-insensitive regular expression; null matches everything. */
  private static Pattern like(String pattern) {
    if (pattern == null) return Pattern.compile(".*", Pattern.DOTALL);
    StringBuilder re = new StringBuilder();
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\' && i + 1 < pattern.length()) {
        re.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
      } else if (c == '%') {
        re.append(".*");
      } else if (c == '_') {
        re.append('.');
      } else {
        re.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  }

  /** [name, "TABLE" or "VIEW"] for every table and view whose name matches. */
  private List<String[]> tables(String schemaPattern, String tablePattern, String[] types)
      throws SQLException {
    List<String[]> out = new ArrayList<>();
    if (!noSchema(schemaPattern)) return out;
    Pattern p = like(tablePattern);
    Set<String> wanted =
        types == null
            ? null
            : new HashSet<>(
                Arrays.asList(types).stream().map(t -> t.toUpperCase(Locale.ROOT)).toList());
    if (wanted == null || wanted.contains("TABLE")) {
      for (Object[] r : run(conn, "SHOW TABLES").rows()) {
        if (p.matcher((String) r[0]).matches()) out.add(new String[] {(String) r[0], "TABLE"});
      }
    }
    if (wanted == null || wanted.contains("VIEW")) {
      for (Object[] r : run(conn, "SHOW VIEWS").rows()) {
        if (p.matcher((String) r[0]).matches()) out.add(new String[] {(String) r[0], "VIEW"});
      }
    }
    out.sort(Comparator.comparing((String[] t) -> t[1]).thenComparing(t -> t[0]));
    return out;
  }

  private ResultSet result(String[] columns, List<Object[]> rows) {
    return new StoolapResultSet(null, columns, rows);
  }

  private ResultSet empty(String... columns) {
    return result(columns, List.of());
  }

  /** The java.sql.Types code of a Stoolap type name as DESCRIBE prints it. */
  private static int sqlType(String typeName) {
    return switch (typeName.toUpperCase(Locale.ROOT)) {
      case "INTEGER" -> Types.BIGINT;
      case "FLOAT" -> Types.DOUBLE;
      case "TEXT" -> Types.VARCHAR;
      case "BOOLEAN" -> Types.BOOLEAN;
      case "TIMESTAMP" -> Types.TIMESTAMP;
      default -> Types.OTHER;
    };
  }

  private static Integer columnSize(int sqlType) {
    return switch (sqlType) {
      case Types.BIGINT -> 19;
      case Types.DOUBLE -> 17;
      case Types.BOOLEAN -> 1;
      case Types.TIMESTAMP -> 35;
      case Types.VARCHAR -> Integer.MAX_VALUE;
      default -> null;
    };
  }

  @Override
  public ResultSet getTables(
      String catalog, String schemaPattern, String tableNamePattern, String[] types)
      throws SQLException {
    List<Object[]> rows = new ArrayList<>();
    for (String[] t : tables(schemaPattern, tableNamePattern, types)) {
      rows.add(new Object[] {null, null, t[0], t[1], null, null, null, null, null, null});
    }
    return result(
        new String[] {
          "TABLE_CAT",
          "TABLE_SCHEM",
          "TABLE_NAME",
          "TABLE_TYPE",
          "REMARKS",
          "TYPE_CAT",
          "TYPE_SCHEM",
          "TYPE_NAME",
          "SELF_REFERENCING_COL_NAME",
          "REF_GENERATION"
        },
        rows);
  }

  @Override
  public ResultSet getColumns(
      String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
      throws SQLException {
    Pattern colPattern = like(columnNamePattern);
    List<Object[]> rows = new ArrayList<>();
    for (String[] t : tables(schemaPattern, tableNamePattern, null)) {
      List<Object[]> cols = new ArrayList<>();
      if (t[1].equals("TABLE")) {
        for (Object[] d : describe(conn, t[0])) {
          cols.add(new Object[] {d[0], ((String) d[1]).toUpperCase(Locale.ROOT), d[2], d[4], d[5]});
        }
      } else {
        // DESCRIBE does not take a view: its column names come from an empty read of it
        for (String name : run(conn, "SELECT * FROM " + quote(t[0]) + " LIMIT 0").columnNames()) {
          cols.add(new Object[] {name, "OTHER", "YES", null, ""});
        }
      }
      int ordinal = 0;
      for (Object[] c : cols) {
        ordinal++;
        String name = (String) c[0];
        if (!colPattern.matcher(name).matches()) continue;
        String typeName = (String) c[1];
        int type = sqlType(typeName);
        boolean nullable = !"NO".equals(c[2]);
        String def = c[3] == null || "".equals(c[3]) ? null : c[3].toString();
        boolean auto = String.valueOf(c[4]).toLowerCase(Locale.ROOT).contains("auto_increment");
        rows.add(
            new Object[] {
              null,
              null,
              t[0],
              name,
              (long) type,
              typeName,
              columnSize(type) == null ? null : (long) columnSize(type),
              null,
              type == Types.DOUBLE ? (Long) 17L : type == Types.BIGINT ? (Long) 0L : null,
              type == Types.BIGINT || type == Types.DOUBLE ? 10L : null,
              (long) (nullable ? columnNullable : columnNoNulls),
              null,
              def,
              null,
              null,
              type == Types.VARCHAR ? (long) Integer.MAX_VALUE : null,
              (long) ordinal,
              nullable ? "YES" : "NO",
              null,
              null,
              null,
              null,
              auto ? "YES" : "NO",
              "NO"
            });
      }
    }
    return result(
        new String[] {
          "TABLE_CAT",
          "TABLE_SCHEM",
          "TABLE_NAME",
          "COLUMN_NAME",
          "DATA_TYPE",
          "TYPE_NAME",
          "COLUMN_SIZE",
          "BUFFER_LENGTH",
          "DECIMAL_DIGITS",
          "NUM_PREC_RADIX",
          "NULLABLE",
          "REMARKS",
          "COLUMN_DEF",
          "SQL_DATA_TYPE",
          "SQL_DATETIME_SUB",
          "CHAR_OCTET_LENGTH",
          "ORDINAL_POSITION",
          "IS_NULLABLE",
          "SCOPE_CATALOG",
          "SCOPE_SCHEMA",
          "SCOPE_TABLE",
          "SOURCE_DATA_TYPE",
          "IS_AUTOINCREMENT",
          "IS_GENERATEDCOLUMN"
        },
        rows);
  }

  /** The single-column primary key of a table, or null; Stoolap keys a table by one column. */
  private String primaryKey(String table) throws SQLException {
    for (Object[] d : describe(conn, table)) {
      if ("PRI".equals(d[3])) return (String) d[0];
    }
    return null;
  }

  /** The exact name of a table, or null when it does not exist. */
  private String tableNamed(String schema, String table) throws SQLException {
    if (table == null || !noSchema(schema)) return null;
    for (String[] t : tables(null, null, new String[] {"TABLE"})) {
      if (t[0].equalsIgnoreCase(table)) return t[0];
    }
    return null;
  }

  @Override
  public ResultSet getPrimaryKeys(String c, String s, String t) throws SQLException {
    List<Object[]> rows = new ArrayList<>();
    String table = tableNamed(s, t);
    String pk = table == null ? null : primaryKey(table);
    if (pk != null) rows.add(new Object[] {null, null, table, pk, 1L, "PRIMARY"});
    return result(
        new String[] {
          "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"
        },
        rows);
  }

  @Override
  public ResultSet getIndexInfo(String c, String s, String t, boolean unique, boolean approximate)
      throws SQLException {
    List<Object[]> rows = new ArrayList<>();
    String table = tableNamed(s, t);
    if (table != null) {
      String pk = primaryKey(table);
      if (pk != null) {
        rows.add(indexRow(table, false, "PRIMARY", tableIndexOther, 1, pk));
      }
      for (Object[] r : run(conn, "SHOW INDEXES FROM " + quote(table)).rows()) {
        boolean isUnique = Boolean.TRUE.equals(r[4]);
        if (unique && !isUnique) continue;
        String cols = (String) r[2];
        if (cols.startsWith("(") && cols.endsWith(")")) cols = cols.substring(1, cols.length() - 1);
        short type = "HASH".equalsIgnoreCase((String) r[3]) ? tableIndexHashed : tableIndexOther;
        int pos = 0;
        for (String col : cols.split(",")) {
          rows.add(indexRow(table, !isUnique, (String) r[1], type, ++pos, col.trim()));
        }
      }
    }
    return result(
        new String[] {
          "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER", "INDEX_NAME",
          "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY", "PAGES",
          "FILTER_CONDITION"
        },
        rows);
  }

  private static Object[] indexRow(
      String table, boolean nonUnique, String name, short type, int pos, String column) {
    return new Object[] {
      null,
      null,
      table,
      nonUnique,
      null,
      name,
      (long) type,
      (long) pos,
      column,
      "A",
      null,
      null,
      null
    };
  }

  private static final Pattern FOREIGN_KEY =
      Pattern.compile(
          "FOREIGN KEY \\(([^)]*)\\) REFERENCES (.+?)\\(([^)]*)\\)"
              + "(?: ON DELETE (CASCADE|SET NULL|SET DEFAULT|RESTRICT|NO ACTION))?"
              + "(?: ON UPDATE (CASCADE|SET NULL|SET DEFAULT|RESTRICT|NO ACTION))?",
          Pattern.CASE_INSENSITIVE);

  private static long rule(String action) {
    if (action == null) return importedKeyNoAction;
    return switch (action.toUpperCase(Locale.ROOT)) {
      case "CASCADE" -> importedKeyCascade;
      case "SET NULL" -> importedKeySetNull;
      case "SET DEFAULT" -> importedKeySetDefault;
      case "RESTRICT" -> importedKeyRestrict;
      default -> importedKeyNoAction;
    };
  }

  /** The foreign keys a table declares, read from SHOW CREATE TABLE. */
  private List<Object[]> foreignKeys(String table) throws SQLException {
    List<Object[]> rows = new ArrayList<>();
    List<Object[]> created = run(conn, "SHOW CREATE TABLE " + quote(table)).rows();
    if (created.isEmpty()) return rows;
    Matcher m = FOREIGN_KEY.matcher((String) created.get(0)[1]);
    while (m.find()) {
      String[] fkCols = m.group(1).split(",");
      String[] pkCols = m.group(3).split(",");
      String parent = unquote(m.group(2).trim());
      for (int i = 0; i < fkCols.length && i < pkCols.length; i++) {
        rows.add(
            new Object[] {
              null,
              null,
              parent,
              unquote(pkCols[i].trim()),
              null,
              null,
              table,
              unquote(fkCols[i].trim()),
              (long) (i + 1),
              rule(m.group(5)),
              rule(m.group(4)),
              "fk_" + table + "_" + unquote(fkCols[0].trim()),
              "PRIMARY",
              (long) importedKeyNotDeferrable
            });
      }
    }
    return rows;
  }

  private static final String[] KEY_COLUMNS = {
    "PKTABLE_CAT",
    "PKTABLE_SCHEM",
    "PKTABLE_NAME",
    "PKCOLUMN_NAME",
    "FKTABLE_CAT",
    "FKTABLE_SCHEM",
    "FKTABLE_NAME",
    "FKCOLUMN_NAME",
    "KEY_SEQ",
    "UPDATE_RULE",
    "DELETE_RULE",
    "FK_NAME",
    "PK_NAME",
    "DEFERRABILITY"
  };

  @Override
  public ResultSet getImportedKeys(String c, String s, String t) throws SQLException {
    String table = tableNamed(s, t);
    return result(KEY_COLUMNS, table == null ? List.of() : foreignKeys(table));
  }

  @Override
  public ResultSet getExportedKeys(String c, String s, String t) throws SQLException {
    return getCrossReference(c, s, t, null, null, null);
  }

  @Override
  public ResultSet getCrossReference(
      String pc, String ps, String pt, String fc, String fs, String ft) throws SQLException {
    List<Object[]> rows = new ArrayList<>();
    String parent = tableNamed(ps, pt);
    if (parent != null) {
      for (String[] t : tables(null, null, new String[] {"TABLE"})) {
        if (ft != null && !t[0].equalsIgnoreCase(ft)) continue;
        if (ft != null && !noSchema(fs)) continue;
        for (Object[] r : foreignKeys(t[0])) {
          if (parent.equalsIgnoreCase((String) r[2])) rows.add(r);
        }
      }
    }
    return result(KEY_COLUMNS, rows);
  }

  @Override
  public ResultSet getTypeInfo() throws SQLException {
    List<Object[]> rows = new ArrayList<>();
    Object[][] types = {
      {"BOOLEAN", Types.BOOLEAN, 1L, false},
      {"INTEGER", Types.BIGINT, 19L, false},
      {"FLOAT", Types.DOUBLE, 17L, false},
      {"TIMESTAMP", Types.TIMESTAMP, 35L, false},
      {"TEXT", Types.VARCHAR, (long) Integer.MAX_VALUE, true},
      {"JSON", Types.OTHER, (long) Integer.MAX_VALUE, true},
      {"VECTOR", Types.OTHER, null, false},
    };
    for (Object[] t : types) {
      boolean text = (Boolean) t[3];
      boolean numeric = t[1].equals(Types.BIGINT) || t[1].equals(Types.DOUBLE);
      rows.add(
          new Object[] {
            t[0],
            (long) (int) t[1],
            t[2],
            text ? "'" : null,
            text ? "'" : null,
            t[0].equals("VECTOR") ? "dimensions" : null,
            (long) typeNullable,
            text,
            (long) (t[0].equals("VECTOR") ? typePredNone : typeSearchable),
            !numeric,
            false,
            t[0].equals("INTEGER"),
            null,
            0L,
            t[1].equals(Types.DOUBLE) ? 17L : 0L,
            null,
            null,
            numeric ? 10L : null
          });
    }
    return result(
        new String[] {
          "TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX",
          "CREATE_PARAMS", "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE",
          "FIXED_PREC_SCALE", "AUTO_INCREMENT", "LOCAL_TYPE_NAME", "MINIMUM_SCALE", "MAXIMUM_SCALE",
          "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "NUM_PREC_RADIX"
        },
        rows);
  }

  @Override
  public ResultSet getTableTypes() throws SQLException {
    List<Object[]> rows = new ArrayList<>();
    rows.add(new Object[] {"TABLE"});
    rows.add(new Object[] {"VIEW"});
    return result(new String[] {"TABLE_TYPE"}, rows);
  }

  @Override
  public ResultSet getSchemas() throws SQLException {
    return empty("TABLE_SCHEM", "TABLE_CATALOG");
  }

  @Override
  public ResultSet getSchemas(String c, String s) throws SQLException {
    return empty("TABLE_SCHEM", "TABLE_CATALOG");
  }

  @Override
  public ResultSet getCatalogs() throws SQLException {
    return empty("TABLE_CAT");
  }

  @Override
  public ResultSet getProcedures(String c, String s, String p) throws SQLException {
    return empty(
        "PROCEDURE_CAT",
        "PROCEDURE_SCHEM",
        "PROCEDURE_NAME",
        "RESERVED1",
        "RESERVED2",
        "RESERVED3",
        "REMARKS",
        "PROCEDURE_TYPE",
        "SPECIFIC_NAME");
  }

  @Override
  public ResultSet getProcedureColumns(String c, String s, String p, String col)
      throws SQLException {
    return empty(
        "PROCEDURE_CAT",
        "PROCEDURE_SCHEM",
        "PROCEDURE_NAME",
        "COLUMN_NAME",
        "COLUMN_TYPE",
        "DATA_TYPE",
        "TYPE_NAME",
        "PRECISION",
        "LENGTH",
        "SCALE",
        "RADIX",
        "NULLABLE",
        "REMARKS",
        "COLUMN_DEF",
        "SQL_DATA_TYPE",
        "SQL_DATETIME_SUB",
        "CHAR_OCTET_LENGTH",
        "ORDINAL_POSITION",
        "IS_NULLABLE",
        "SPECIFIC_NAME");
  }

  @Override
  public ResultSet getColumnPrivileges(String c, String s, String t, String col)
      throws SQLException {
    return empty(
        "TABLE_CAT",
        "TABLE_SCHEM",
        "TABLE_NAME",
        "COLUMN_NAME",
        "GRANTOR",
        "GRANTEE",
        "PRIVILEGE",
        "IS_GRANTABLE");
  }

  @Override
  public ResultSet getTablePrivileges(String c, String s, String t) throws SQLException {
    return empty(
        "TABLE_CAT",
        "TABLE_SCHEM",
        "TABLE_NAME",
        "GRANTOR",
        "GRANTEE",
        "PRIVILEGE",
        "IS_GRANTABLE");
  }

  @Override
  public ResultSet getBestRowIdentifier(String c, String s, String t, int scope, boolean nullable)
      throws SQLException {
    List<Object[]> rows = new ArrayList<>();
    String table = tableNamed(s, t);
    String pk = table == null ? null : primaryKey(table);
    if (pk != null) {
      rows.add(
          new Object[] {
            (long) bestRowSession,
            pk,
            (long) Types.BIGINT,
            "INTEGER",
            19L,
            null,
            0L,
            (long) bestRowNotPseudo
          });
    }
    return result(
        new String[] {
          "SCOPE",
          "COLUMN_NAME",
          "DATA_TYPE",
          "TYPE_NAME",
          "COLUMN_SIZE",
          "BUFFER_LENGTH",
          "DECIMAL_DIGITS",
          "PSEUDO_COLUMN"
        },
        rows);
  }

  @Override
  public ResultSet getVersionColumns(String c, String s, String t) throws SQLException {
    return empty(
        "SCOPE",
        "COLUMN_NAME",
        "DATA_TYPE",
        "TYPE_NAME",
        "COLUMN_SIZE",
        "BUFFER_LENGTH",
        "DECIMAL_DIGITS",
        "PSEUDO_COLUMN");
  }

  @Override
  public ResultSet getUDTs(String c, String s, String t, int[] types) throws SQLException {
    return empty(
        "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS", "BASE_TYPE");
  }

  @Override
  public ResultSet getSuperTypes(String c, String s, String t) throws SQLException {
    return empty(
        "TYPE_CAT",
        "TYPE_SCHEM",
        "TYPE_NAME",
        "SUPERTYPE_CAT",
        "SUPERTYPE_SCHEM",
        "SUPERTYPE_NAME");
  }

  @Override
  public ResultSet getSuperTables(String c, String s, String t) throws SQLException {
    return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME");
  }

  @Override
  public ResultSet getAttributes(String c, String s, String t, String a) throws SQLException {
    return empty(
        "TYPE_CAT",
        "TYPE_SCHEM",
        "TYPE_NAME",
        "ATTR_NAME",
        "DATA_TYPE",
        "ATTR_TYPE_NAME",
        "ATTR_SIZE",
        "DECIMAL_DIGITS",
        "NUM_PREC_RADIX",
        "NULLABLE",
        "REMARKS",
        "ATTR_DEF",
        "SQL_DATA_TYPE",
        "SQL_DATETIME_SUB",
        "CHAR_OCTET_LENGTH",
        "ORDINAL_POSITION",
        "IS_NULLABLE",
        "SCOPE_CATALOG",
        "SCOPE_SCHEMA",
        "SCOPE_TABLE",
        "SOURCE_DATA_TYPE");
  }

  @Override
  public ResultSet getClientInfoProperties() throws SQLException {
    return empty("NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION");
  }

  @Override
  public ResultSet getFunctions(String c, String s, String f) throws SQLException {
    return empty(
        "FUNCTION_CAT",
        "FUNCTION_SCHEM",
        "FUNCTION_NAME",
        "REMARKS",
        "FUNCTION_TYPE",
        "SPECIFIC_NAME");
  }

  @Override
  public ResultSet getFunctionColumns(String c, String s, String f, String col)
      throws SQLException {
    return empty(
        "FUNCTION_CAT",
        "FUNCTION_SCHEM",
        "FUNCTION_NAME",
        "COLUMN_NAME",
        "COLUMN_TYPE",
        "DATA_TYPE",
        "TYPE_NAME",
        "PRECISION",
        "LENGTH",
        "SCALE",
        "RADIX",
        "NULLABLE",
        "REMARKS",
        "CHAR_OCTET_LENGTH",
        "ORDINAL_POSITION",
        "IS_NULLABLE",
        "SPECIFIC_NAME");
  }

  @Override
  public ResultSet getPseudoColumns(String c, String s, String t, String col) throws SQLException {
    return empty(
        "TABLE_CAT",
        "TABLE_SCHEM",
        "TABLE_NAME",
        "COLUMN_NAME",
        "DATA_TYPE",
        "COLUMN_SIZE",
        "DECIMAL_DIGITS",
        "NUM_PREC_RADIX",
        "COLUMN_USAGE",
        "REMARKS",
        "CHAR_OCTET_LENGTH",
        "IS_NULLABLE");
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) return iface.cast(this);
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass());
  }
}
