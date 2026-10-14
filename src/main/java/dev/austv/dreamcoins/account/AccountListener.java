package dev.austv.dreamcoins.account;

import dev.austv.dreamcoins.config.Messages;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Carrega a conta antes da entrada e a tira do cache na saída. */
public final class AccountListener implements Listener {

  private static final long LOAD_TIMEOUT_MILLIS = 10_000;

  private final AccountService accounts;
  private final Messages messages;
  private final Logger logger;

  public AccountListener(AccountService accounts, Messages messages, Logger logger) {
    this.accounts = accounts;
    this.messages = messages;
    this.logger = logger;
  }

  /**
   * Carrega no pré-login, que é assíncrono justamente para isso: no join o saldo já está no cache,
   * e outros plugins que pagam algo na entrada encontram a conta pronta.
   *
   * <p>Se o banco falhar, o jogador é recusado. Entrar com saldo desconhecido seria pior: as
   * mutações seriam recusadas por "conta não carregada" o tempo inteiro, sem explicação.
   */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPreLogin(AsyncPlayerPreLoginEvent event) {
    if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
      return;
    }
    try {
      accounts.preload(event.getUniqueId(), event.getName(), LOAD_TIMEOUT_MILLIS);
    } catch (Exception error) {
      logger.log(Level.SEVERE, "Falha ao carregar a conta de " + event.getName(), error);
      event.disallow(
          AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("errors.account-load-failed"));
    }
  }

  /** LOWEST: a conta entra no cache antes de qualquer outro plugin reagir ao join. */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onJoin(PlayerJoinEvent event) {
    accounts.join(event.getPlayer().getUniqueId(), event.getPlayer().getName());
  }

  /** MONITOR: a conta sai do cache depois de todo plugin que ainda queira mexer nela na saída. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    accounts.quit(event.getPlayer().getUniqueId());
  }
}
