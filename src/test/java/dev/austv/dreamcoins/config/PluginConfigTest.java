package dev.austv.dreamcoins.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.austv.dreamcoins.money.Money;
import dev.austv.dreamcoins.storage.StorageType;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PluginConfigTest {

  private final List<String> warnings = new ArrayList<>();

  private PluginConfig parse(String yaml) throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    config.loadFromString(yaml);
    return PluginConfig.from(config, warnings::add);
  }

  @Test
  void emptyConfigFallsBackToDefaults() throws Exception {
    PluginConfig config = parse("");
    assertEquals(StorageType.SQLITE, config.storageType());
    assertEquals("dreamcoins.db", config.sqliteFile());
    assertEquals(0, config.startingBalance());
    assertEquals(PluginConfig.MAX_BALANCE_CEILING, config.maxBalance());
    assertEquals(10, config.flushIntervalSeconds());
    assertEquals(30, config.resyncIntervalSeconds());
    assertTrue(warnings.isEmpty());
  }

  @Test
  void moneyValuesAreWrittenLikeInGame() throws Exception {
    PluginConfig config =
        parse(
            """
            accounts:
              starting-balance: "1 ouro 50 prata"
              max-balance: "1000 ouro"
            """);
    assertEquals(Money.of(1, 50, 0).totalBronze(), config.startingBalance());
    assertEquals(Money.of(1000, 0, 0).totalBronze(), config.maxBalance());
  }

  @Test
  void invalidValuesWarnAndUseTheDefault() throws Exception {
    PluginConfig config =
        parse(
            """
            storage:
              type: postgres
            accounts:
              starting-balance: "10 cobre"
              flush-interval-seconds: 0
            """);
    assertEquals(StorageType.SQLITE, config.storageType());
    assertEquals(0, config.startingBalance());
    assertEquals(10, config.flushIntervalSeconds());
    assertEquals(3, warnings.size());
  }

  @Test
  void maxBalanceIsCappedSoVaultDoublesStayExact() throws Exception {
    PluginConfig config =
        parse(
            """
            accounts:
              max-balance: "999999999999 ouro"
            """);
    assertEquals(PluginConfig.MAX_BALANCE_CEILING, config.maxBalance());
    assertEquals(1, warnings.size());
  }

  @Test
  void zeroMaxBalanceMeansTheAbsoluteCeilingWithoutWarning() throws Exception {
    PluginConfig config =
        parse(
            """
            accounts:
              max-balance: "0"
            """);
    assertEquals(PluginConfig.MAX_BALANCE_CEILING, config.maxBalance());
    assertTrue(warnings.isEmpty());
  }

  @Test
  void startingBalanceAboveMaxIsCapped() throws Exception {
    PluginConfig config =
        parse(
            """
            accounts:
              starting-balance: "20 ouro"
              max-balance: "10 ouro"
            """);
    assertEquals(Money.of(10, 0, 0).totalBronze(), config.startingBalance());
    assertEquals(1, warnings.size());
  }

  @Test
  void mysqlCredentials() throws Exception {
    PluginConfig config =
        parse(
            """
            storage:
              type: mysql
              mysql:
                host: db.local
                port: 3307
                database: coins
                username: u
                password: p
                pool-size: 3
            """);
    assertEquals(StorageType.MYSQL, config.storageType());
    assertEquals("db.local", config.mysql().host());
    assertEquals(3307, config.mysql().port());
    assertEquals(3, config.mysql().poolSize());
  }
}
