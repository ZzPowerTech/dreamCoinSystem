package dev.austv.dreamcoins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.austv.dreamcoins.config.PluginConfig;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Trava os arquivos que vão no jar. */
class ResourcesTest {

  private static YamlConfiguration load(String name) throws Exception {
    try (InputStream in = ResourcesTest.class.getClassLoader().getResourceAsStream(name)) {
      assertNotNull(in, name);
      return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
  }

  /** Colidem com o Essentials instalado. */
  @Test
  void neverRegistersEssentialsCommands() throws Exception {
    ConfigurationSection commands = load("plugin.yml").getConfigurationSection("commands");
    assertNotNull(commands);
    Set<String> forbidden = Set.of("pay", "balance", "bal", "money", "eco", "economy", "baltop");
    for (String name : commands.getKeys(false)) {
      assertFalse(forbidden.contains(name), "comando proibido: " + name);
      for (String alias : commands.getStringList(name + ".aliases")) {
        assertFalse(forbidden.contains(alias), "alias proibido: " + alias);
      }
    }
  }

  /** O Vault é a API principal: sem o depend o plugin poderia subir antes dele e não registrar. */
  @Test
  void dependsOnVault() throws Exception {
    assertTrue(load("plugin.yml").getStringList("depend").contains("Vault"));
  }

  /** Toda chave que o código usa existe no messages.yml do jar. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "prefix",
        "balance.self",
        "balance.other",
        "usage.saldo",
        "errors.no-permission",
        "errors.unknown-player",
        "errors.account-loading",
        "errors.database",
        "errors.account-load-failed",
        "coins.gold.singular",
        "coins.silver.symbol",
        "coins.bronze.plural",
        "format.last-separator",
        "placeholders.no-coin"
      })
  void messageKeysExist(String key) throws Exception {
    assertTrue(load("messages.yml").isString(key), key);
  }

  /** O config.yml do jar sobe sem nenhum aviso. */
  @Test
  void bundledConfigIsValid() throws Exception {
    List<String> warnings = new ArrayList<>();
    PluginConfig.from(load("config.yml"), warnings::add);
    assertTrue(warnings.isEmpty(), warnings.toString());
  }
}
