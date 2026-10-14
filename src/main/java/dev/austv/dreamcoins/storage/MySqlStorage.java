package dev.austv.dreamcoins.storage;

import com.zaxxer.hikari.HikariConfig;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Backend em MySQL. Diferente do {@code ausAchievements}, <b>pode</b> ser compartilhado entre
 * servidores: como tudo é gravado por delta, dois servidores mexendo na mesma conta somam em vez de
 * se sobrescrever, e o resync periódico traz para o cache o que o outro servidor gravou.
 */
public final class MySqlStorage extends SqlStorage {

  /** Parâmetros de conexão vindos do {@code config.yml}. */
  public record Credentials(
      String host, int port, String database, String username, String password, int poolSize) {

    @Override
    public String toString() {
      // Nunca deixar a senha cair num log por acidente.
      return "Credentials[" + username + "@" + host + ":" + port + "/" + database + "]";
    }
  }

  private final Credentials credentials;

  public MySqlStorage(Credentials credentials) {
    this.credentials = credentials;
  }

  @Override
  protected HikariConfig hikariConfig() {
    HikariConfig config = new HikariConfig();
    config.setPoolName("dreamCoins-mysql");
    config.setJdbcUrl(
        "jdbc:mysql://"
            + credentials.host()
            + ":"
            + credentials.port()
            + "/"
            + credentials.database()
            + "?useUnicode=true&characterEncoding=utf8");
    config.setUsername(credentials.username());
    config.setPassword(credentials.password());
    config.setMaximumPoolSize(Math.max(1, credentials.poolSize()));
    // MySQL hospedado fecha conexão ociosa por `wait_timeout`; o padrão do Hikari (30 min) fica
    // atrás dele. Mesmos valores que o ausAchievements validou em produção.
    config.setMaxLifetime(TimeUnit.MINUTES.toMillis(4));
    config.setIdleTimeout(TimeUnit.MINUTES.toMillis(2));
    config.setKeepaliveTime(TimeUnit.MINUTES.toMillis(1));
    config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(10));
    return config;
  }

  @Override
  protected List<String> schemaStatements() {
    // O índice vai dentro do CREATE TABLE: o MySQL não tem CREATE INDEX IF NOT EXISTS.
    // A collation padrão (utf8mb4_0900_ai_ci) já compara nomes sem diferenciar maiúsculas.
    return List.of(
        """
        CREATE TABLE IF NOT EXISTS dc_schema (
          version INT NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE IF NOT EXISTS dc_accounts (
          uuid       VARCHAR(36) NOT NULL,
          name       VARCHAR(32) NOT NULL,
          balance    BIGINT      NOT NULL DEFAULT 0,
          updated_at BIGINT      NOT NULL DEFAULT 0,
          PRIMARY KEY (uuid),
          KEY idx_dc_accounts_name (name),
          CONSTRAINT chk_dc_balance CHECK (balance >= 0)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
  }

  @Override
  protected String nameMatchSql() {
    return "name = ?";
  }

  @Override
  protected String insertIgnoreSql() {
    return "INSERT IGNORE INTO dc_accounts(uuid, name, balance, updated_at) VALUES(?, ?, ?, ?)";
  }
}
