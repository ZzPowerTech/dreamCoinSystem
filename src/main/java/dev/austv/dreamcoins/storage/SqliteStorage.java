package dev.austv.dreamcoins.storage;

import com.zaxxer.hikari.HikariConfig;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Backend em arquivo local. Padrão para servidor único. */
public final class SqliteStorage extends SqlStorage {

  private final Path file;

  public SqliteStorage(Path file) {
    this.file = file;
  }

  @Override
  protected HikariConfig hikariConfig() {
    HikariConfig config = new HikariConfig();
    config.setPoolName("dreamCoins-sqlite");
    config.setDriverClassName("org.sqlite.JDBC");
    config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
    // SQLite aceita um único escritor: um pool maior só geraria SQLITE_BUSY.
    config.setMaximumPoolSize(1);
    // Se outro processo segurar o arquivo, espera em vez de falhar na hora.
    config.setConnectionInitSql("PRAGMA busy_timeout = 5000");
    return config;
  }

  @Override
  protected void afterOpen(Connection connection) throws SQLException {
    // WAL é gravado no próprio arquivo: basta ligar uma vez. Leitura não bloqueia escrita.
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode = WAL");
    }
  }

  @Override
  protected List<String> schemaStatements() {
    return List.of(
        """
        CREATE TABLE IF NOT EXISTS dc_schema (
          version INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS dc_accounts (
          uuid       TEXT    NOT NULL PRIMARY KEY,
          name       TEXT    NOT NULL,
          balance    INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0),
          updated_at INTEGER NOT NULL DEFAULT 0,
          last_seen  INTEGER NOT NULL DEFAULT 0
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_dc_accounts_name ON dc_accounts(name COLLATE NOCASE)");
  }

  @Override
  protected String nameMatchSql() {
    return "name = ? COLLATE NOCASE";
  }

  @Override
  protected String insertIgnoreSql() {
    return "INSERT OR IGNORE INTO dc_accounts(uuid, name, balance, updated_at, last_seen)"
        + " VALUES(?, ?, ?, ?, ?)";
  }
}
