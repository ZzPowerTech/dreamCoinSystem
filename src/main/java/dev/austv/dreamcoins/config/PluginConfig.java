package dev.austv.dreamcoins.config;

import dev.austv.dreamcoins.money.Money;
import dev.austv.dreamcoins.money.MoneyParser;
import dev.austv.dreamcoins.storage.MySqlStorage;
import dev.austv.dreamcoins.storage.StorageType;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * O {@code config.yml} lido e validado. Valor inválido nunca derruba o plugin: vira aviso no log
 * e cai no padrão — o aviso nomeia a chave, que é onde o admin precisa olhar.
 */
public record PluginConfig(
    StorageType storageType,
    String sqliteFile,
    MySqlStorage.Credentials mysql,
    long startingBalance,
    long maxBalance,
    int flushIntervalSeconds,
    int resyncIntervalSeconds,
    int vaultTimeoutMillis) {

  /**
   * Teto absoluto do saldo, em bronze (9.999.999.999 ouro, 999 prata e 99 bronze). O Vault recebe
   * {@code bronze / 100} num {@code double}, e só decimais de até 15 dígitos significativos voltam
   * idênticos de um {@code double} — este é o maior saldo de 15 dígitos. Um teto abaixo de 2^53
   * não basta: {@code 8_999_999_999_999_999 / 100} já volta como {@code …998}.
   */
  public static final long MAX_BALANCE_CEILING = 999_999_999_999_999L;

  public static PluginConfig from(ConfigurationSection root, Consumer<String> warn) {
    String rawType = root.getString("storage.type", "sqlite");
    StorageType type =
        StorageType.parse(rawType)
            .orElseGet(
                () -> {
                  warn.accept("storage.type '" + rawType + "' desconhecido; usando sqlite.");
                  return StorageType.SQLITE;
                });

    MySqlStorage.Credentials mysql =
        new MySqlStorage.Credentials(
            root.getString("storage.mysql.host", "localhost"),
            root.getInt("storage.mysql.port", 3306),
            root.getString("storage.mysql.database", "dreamcoins"),
            root.getString("storage.mysql.username", "dreamcoins"),
            root.getString("storage.mysql.password", ""),
            root.getInt("storage.mysql.pool-size", 5),
            root.getBoolean("storage.mysql.use-ssl", false));

    // "0" é o "sem teto próprio" do parseConfig: vale o teto absoluto, sem aviso.
    long max = money(root, "accounts.max-balance", MAX_BALANCE_CEILING, warn);
    if (max == 0) {
      max = MAX_BALANCE_CEILING;
    } else if (max > MAX_BALANCE_CEILING) {
      warn.accept("accounts.max-balance acima do teto absoluto (9999999999 ouro); usando o teto.");
      max = MAX_BALANCE_CEILING;
    }
    long starting = money(root, "accounts.starting-balance", 0, warn);
    if (starting > max) {
      warn.accept("accounts.starting-balance maior que accounts.max-balance; usando o máximo.");
      starting = max;
    }

    return new PluginConfig(
        type,
        root.getString("storage.sqlite.file", "dreamcoins.db"),
        mysql,
        starting,
        max,
        positive(root, "accounts.flush-interval-seconds", 10, warn),
        positive(root, "accounts.resync-interval-seconds", 30, warn),
        positive(root, "vault.offline-timeout-millis", 5000, warn));
  }

  private static long money(
      ConfigurationSection root, String key, long fallback, Consumer<String> warn) {
    if (!root.contains(key)) {
      return fallback;
    }
    String raw = root.getString(key, "");
    Optional<Money> parsed;
    try {
      parsed = MoneyParser.parseConfig(raw);
    } catch (ArithmeticException overflow) {
      parsed = Optional.empty();
    }
    if (parsed.isEmpty()) {
      warn.accept(key + " '" + raw + "' não é uma quantia válida (ex.: \"1 ouro 50 prata\").");
      return fallback;
    }
    return parsed.get().totalBronze();
  }

  private static int positive(
      ConfigurationSection root, String key, int fallback, Consumer<String> warn) {
    int value = root.getInt(key, fallback);
    if (value <= 0) {
      warn.accept(key + " precisa ser maior que zero; usando " + fallback + ".");
      return fallback;
    }
    return value;
  }
}
