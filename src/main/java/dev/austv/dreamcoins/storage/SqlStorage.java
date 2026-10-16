package dev.austv.dreamcoins.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.austv.dreamcoins.account.AccountLedger.Delta;
import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import dev.austv.dreamcoins.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** SQL comum aos dois backends; os dialetos só fornecem pool, schema e o insert-ignore. */
public abstract class SqlStorage implements Storage {

  static final int SCHEMA_VERSION = 1;

  /** Teto de parâmetros por {@code IN (...)}: o SQLite antigo recusa mais de 999. */
  private static final int IN_CHUNK = 500;

  private HikariDataSource dataSource;

  protected abstract HikariConfig hikariConfig();

  protected abstract List<String> schemaStatements();

  /**
   * {@code INSERT} que não falha se a conta já existe: (uuid, name, balance, updated_at,
   * last_seen).
   */
  protected abstract String insertIgnoreSql();

  /**
   * Condição que compara {@code name} com o parâmetro sem diferenciar maiúsculas, escrita de forma
   * que o índice do dialeto seja usado ({@code LOWER(name)} ignoraria o índice).
   */
  protected abstract String nameMatchSql();

  /** Executado uma vez depois de abrir o pool, antes do schema. */
  protected void afterOpen(Connection connection) throws SQLException {}

  @Override
  public void open() throws SQLException {
    dataSource = new HikariDataSource(hikariConfig());
    try (Connection connection = dataSource.getConnection()) {
      afterOpen(connection);
      try (Statement statement = connection.createStatement()) {
        for (String sql : schemaStatements()) {
          statement.execute(sql);
        }
      }
      stampSchemaVersion(connection);
    }
  }

  private static void stampSchemaVersion(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM dc_schema")) {
      rs.next();
      if (rs.getInt(1) > 0) {
        return;
      }
    }
    try (PreparedStatement insert =
        connection.prepareStatement("INSERT INTO dc_schema(version) VALUES(?)")) {
      insert.setInt(1, SCHEMA_VERSION);
      insert.executeUpdate();
    }
  }

  @Override
  public long loadOrCreate(UUID uuid, String name, long startingBalance) throws SQLException {
    return inTransaction(
        connection -> {
          long now = System.currentTimeMillis();
          try (PreparedStatement insert = connection.prepareStatement(insertIgnoreSql())) {
            insert.setString(1, uuid.toString());
            insert.setString(2, name);
            insert.setLong(3, startingBalance);
            insert.setLong(4, now);
            insert.setLong(5, now);
            insert.executeUpdate();
          }
          // last_seen só muda aqui: é ele que decide de quem é um nome reaproveitado.
          try (PreparedStatement rename =
              connection.prepareStatement(
                  "UPDATE dc_accounts SET name = ?, last_seen = ? WHERE uuid = ?")) {
            rename.setString(1, name);
            rename.setLong(2, now);
            rename.setString(3, uuid.toString());
            rename.executeUpdate();
          }
          return balanceOf(connection, uuid).orElseThrow();
        });
  }

  @Override
  public boolean ensureAccount(UUID uuid, String name, long startingBalance) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert = connection.prepareStatement(insertIgnoreSql())) {
      insert.setString(1, uuid.toString());
      insert.setString(2, name == null ? "" : name);
      insert.setLong(3, startingBalance);
      insert.setLong(4, System.currentTimeMillis());
      insert.setLong(5, 0);
      return insert.executeUpdate() == 1;
    }
  }

  @Override
  public List<Delta> applyDeltas(List<Delta> deltas) throws SQLException {
    List<Delta> nonZero = deltas.stream().filter(d -> d.amount() != 0).toList();
    if (nonZero.isEmpty()) {
      return List.of();
    }
    return inTransaction(
        connection -> {
          long now = System.currentTimeMillis();
          int[] counts;
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                  UPDATE dc_accounts SET balance = balance + ?, updated_at = ?
                  WHERE uuid = ? AND balance + ? >= 0
                  """)) {
            for (Delta delta : nonZero) {
              update.setLong(1, delta.amount());
              update.setLong(2, now);
              update.setString(3, delta.uuid().toString());
              update.setLong(4, delta.amount());
              update.addBatch();
            }
            counts = update.executeBatch();
          }
          List<Delta> unapplied = new ArrayList<>();
          for (int i = 0; i < counts.length; i++) {
            if (counts[i] != 0) {
              continue; // 1, ou SUCCESS_NO_INFO
            }
            unapplied.add(clampToZero(connection, nonZero.get(i), now));
          }
          return unapplied;
        });
  }

  /**
   * O delta deixaria o saldo negativo: aplica o que dá (zera) e devolve o resto. Só acontece com
   * escrita concorrente de outro servidor — o cache deste já validou o saldo —, e quem chama
   * registra o resto, porque ele é dinheiro gasto que o banco não tinha.
   */
  private static Delta clampToZero(Connection connection, Delta delta, long now)
      throws SQLException {
    OptionalLong current = balanceOf(connection, delta.uuid());
    if (current.isEmpty()) {
      return delta; // conta apagada do banco: nada aplicado
    }
    try (PreparedStatement zero =
        connection.prepareStatement(
            "UPDATE dc_accounts SET balance = 0, updated_at = ? WHERE uuid = ?")) {
      zero.setLong(1, now);
      zero.setString(2, delta.uuid().toString());
      zero.executeUpdate();
    }
    return new Delta(delta.uuid(), delta.amount() + current.getAsLong());
  }

  @Override
  public GuardedResult applyGuarded(UUID uuid, long delta, long maxBalance) throws SQLException {
    // Fora da faixa nem chega ao SQL: o MySQL estouraria BIGINT em vez de recusar.
    // Fora da faixa nem chega ao SQL: o MySQL estouraria BIGINT em vez de recusar. Débito é
    // limitado pelo teto ABSOLUTO, não pelo configurado: um max-balance reduzido abaixo de saldos
    // existentes não pode impedir de tirar dinheiro de quem passou dele.
    if (delta > maxBalance || delta < -Money.MAX_TOTAL || delta == 0) {
      OptionalLong current = balance(uuid);
      if (current.isEmpty()) {
        return new GuardedResult(Outcome.NO_ACCOUNT, 0);
      }
      Outcome outcome =
          delta == 0 ? Outcome.OK : delta < 0 ? Outcome.INSUFFICIENT : Outcome.EXCEEDS_MAX;
      return new GuardedResult(outcome, current.getAsLong());
    }
    return inTransaction(
        connection -> {
          int changed;
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                  UPDATE dc_accounts SET balance = balance + ?, updated_at = ?
                  WHERE uuid = ? AND balance + ? >= 0 AND (? < 0 OR balance + ? <= ?)
                  """)) {
            update.setLong(1, delta);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, uuid.toString());
            update.setLong(4, delta);
            update.setLong(5, delta);
            update.setLong(6, delta);
            update.setLong(7, maxBalance);
            changed = update.executeUpdate();
          }
          OptionalLong balance = balanceOf(connection, uuid);
          if (balance.isEmpty()) {
            return new GuardedResult(Outcome.NO_ACCOUNT, 0);
          }
          if (changed == 1) {
            return new GuardedResult(Outcome.OK, balance.getAsLong());
          }
          Outcome refusal = delta < 0 ? Outcome.INSUFFICIENT : Outcome.EXCEEDS_MAX;
          return new GuardedResult(refusal, balance.getAsLong());
        });
  }

  @Override
  public OptionalLong balance(UUID uuid) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      return balanceOf(connection, uuid);
    }
  }

  @Override
  public Optional<StoredAccount> findByName(String name) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT uuid, name, balance FROM dc_accounts WHERE "
                    + nameMatchSql()
                    + " ORDER BY last_seen DESC LIMIT 1")) {
      select.setString(1, name);
      try (ResultSet rs = select.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new StoredAccount(
                UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
      }
    }
  }

  @Override
  public Map<UUID, Long> balances(Collection<UUID> uuids) throws SQLException {
    Map<UUID, Long> out = new HashMap<>();
    List<UUID> all = new ArrayList<>(uuids);
    try (Connection connection = dataSource.getConnection()) {
      for (int from = 0; from < all.size(); from += IN_CHUNK) {
        List<UUID> chunk = all.subList(from, Math.min(all.size(), from + IN_CHUNK));
        String marks = String.join(",", Collections.nCopies(chunk.size(), "?"));
        try (PreparedStatement select =
            connection.prepareStatement(
                "SELECT uuid, balance FROM dc_accounts WHERE uuid IN (" + marks + ")")) {
          for (int i = 0; i < chunk.size(); i++) {
            select.setString(i + 1, chunk.get(i).toString());
          }
          try (ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
              out.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
            }
          }
        }
      }
    }
    return out;
  }

  @Override
  public void close() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  private static OptionalLong balanceOf(Connection connection, UUID uuid) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT balance FROM dc_accounts WHERE uuid = ?")) {
      select.setString(1, uuid.toString());
      try (ResultSet rs = select.executeQuery()) {
        return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
      }
    }
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }

  private <T> T inTransaction(SqlWork<T> work) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        T result = work.run(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    }
  }
}
