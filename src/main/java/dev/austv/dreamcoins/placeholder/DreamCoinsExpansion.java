package dev.austv.dreamcoins.placeholder;

import dev.austv.dreamcoins.account.AccountService;
import java.util.OptionalLong;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * {@code %dreamcoins_<params>%}. Casca fina sobre o {@link PlaceholderResolver}.
 *
 * <p>Lê o saldo pelo <b>espelho</b> do cache ({@link AccountService#snapshot}), porque o TAB e os
 * scoreboards pedem placeholders de threads assíncronas e o cache só se toca na main thread.
 * Jogador fora do cache (offline) lê como zero: consultar o banco a cada atualização de scoreboard
 * seria uma consulta por jogador por tick.
 */
public final class DreamCoinsExpansion extends PlaceholderExpansion {

  private final Plugin plugin;
  private final AccountService accounts;
  private final PlaceholderResolver resolver;

  public DreamCoinsExpansion(Plugin plugin, AccountService accounts, PlaceholderResolver resolver) {
    this.plugin = plugin;
    this.accounts = accounts;
    this.resolver = resolver;
  }

  @Override
  public String getIdentifier() {
    return "dreamcoins";
  }

  @Override
  public String getAuthor() {
    return String.join(", ", plugin.getPluginMeta().getAuthors());
  }

  @Override
  public String getVersion() {
    return plugin.getPluginMeta().getVersion();
  }

  /** Sobrevive ao {@code /papi reload}: a expansão é registrada pelo plugin, não baixada. */
  @Override
  public boolean persist() {
    return true;
  }

  @Override
  public String onRequest(OfflinePlayer player, String params) {
    OptionalLong balance =
        player == null ? OptionalLong.empty() : accounts.snapshot(player.getUniqueId());
    return resolver.resolve(params, balance);
  }
}
