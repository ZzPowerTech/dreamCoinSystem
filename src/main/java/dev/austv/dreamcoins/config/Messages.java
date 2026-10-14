package dev.austv.dreamcoins.config;

import dev.austv.dreamcoins.money.CoinType;
import dev.austv.dreamcoins.money.MoneyFormatter;
import dev.austv.dreamcoins.money.MoneyFormatter.CoinName;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Os textos do {@code messages.yml} em MiniMessage, e os nomes das moedas que alimentam o {@link
 * MoneyFormatter}.
 *
 * <p>O arquivo do jar é o padrão de cada chave: chave nova numa versão nova é acrescentada ao
 * arquivo do servidor sem tocar nas que o admin editou, e chave apagada cai no texto do jar em vez
 * de sumir da tela.
 *
 * <p>Valores vindos do jogador (nome, quantia) entram sempre por {@code Placeholder.unparsed}: um
 * texto com {@code <red>} dentro aparece literal, sem virar formatação.
 */
public final class Messages {

  private static final MiniMessage MINI = MiniMessage.miniMessage();

  private final JavaPlugin plugin;
  private final File file;
  private volatile YamlConfiguration loaded = new YamlConfiguration();
  private volatile MoneyFormatter formatter = MoneyFormatter.defaults();

  public Messages(JavaPlugin plugin) {
    this.plugin = plugin;
    this.file = new File(plugin.getDataFolder(), "messages.yml");
    reload();
  }

  public void reload() {
    if (!file.exists()) {
      plugin.saveResource("messages.yml", false);
    }
    YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
    try (InputStream bundled = plugin.getResource("messages.yml")) {
      if (bundled != null) {
        config.setDefaults(
            YamlConfiguration.loadConfiguration(
                new InputStreamReader(bundled, StandardCharsets.UTF_8)));
        config.options().copyDefaults(true);
        config.save(file);
      }
    } catch (IOException error) {
      plugin.getLogger().log(Level.WARNING, "Não consegui atualizar o messages.yml", error);
    }
    loaded = config;
    formatter = buildFormatter(config);
  }

  public MoneyFormatter formatter() {
    return formatter;
  }

  /** Componente da chave, sem prefixo. */
  public Component get(String key, TagResolver... resolvers) {
    return MINI.deserialize(loaded.getString(key, key), resolvers);
  }

  /** Envia a chave com o prefixo comum. Chave vazia no arquivo = mensagem desligada. */
  public void send(CommandSender to, String key, TagResolver... resolvers) {
    String raw = loaded.getString(key, key);
    if (raw.isEmpty()) {
      return;
    }
    to.sendMessage(MINI.deserialize(loaded.getString("prefix", "") + raw, resolvers));
  }

  private static MoneyFormatter buildFormatter(YamlConfiguration config) {
    Map<CoinType, CoinName> names = new EnumMap<>(CoinType.class);
    for (CoinType type : CoinType.values()) {
      CoinName fallback = MoneyFormatter.defaultName(type);
      String base = "coins." + type.name().toLowerCase(Locale.ROOT) + ".";
      names.put(
          type,
          new CoinName(
              config.getString(base + "singular", fallback.singular()),
              config.getString(base + "plural", fallback.plural()),
              config.getString(base + "symbol", fallback.symbol())));
    }
    return new MoneyFormatter(
        names,
        config.getString("format.separator", ", "),
        config.getString("format.last-separator", " e "),
        config.getString("format.thousands-separator", "."));
  }
}
