package dev.austv.dreamcoins.vault;

import dev.austv.dreamcoins.account.AccountService;
import dev.austv.dreamcoins.money.VaultAmounts;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * O provedor de {@link Economy} do Vault. Casca fina: traduz jogador e nome para UUID, garante a
 * main thread e delega ao {@link VaultBridge}.
 *
 * <p><b>Mundo é ignorado</b> — o saldo é um só no servidor inteiro. <b>Bancos não existem</b> (as
 * chamadas devolvem {@code NOT_IMPLEMENTED}).
 *
 * <p><b>Chamada fora da main thread</b> (alguns plugins consultam o Vault de tarefas assíncronas):
 * o cache só é tocado na main thread, então a chamada é levada para lá e esperada. Se a main thread
 * não atender a tempo — ela pode estar justamente esperando a thread que chamou —, a resposta é
 * falha, e o aviso sai uma vez só.
 */
public final class DreamEconomy implements Economy {

  private static final String NO_BANKS = "dreamCoinSystem não tem bancos.";

  private final Plugin plugin;
  private final AccountService accounts;
  private final VaultBridge bridge;
  private final long timeoutMillis;
  private volatile boolean warnedAsync;

  public DreamEconomy(
      Plugin plugin, AccountService accounts, VaultBridge bridge, long timeoutMillis) {
    this.plugin = plugin;
    this.accounts = accounts;
    this.bridge = bridge;
    this.timeoutMillis = timeoutMillis;
  }

  // ---------------------------------------------------------------- identidade

  @Override
  public boolean isEnabled() {
    return plugin.isEnabled();
  }

  @Override
  public String getName() {
    return "dreamCoinSystem";
  }

  @Override
  public boolean hasBankSupport() {
    return false;
  }

  @Override
  public int fractionalDigits() {
    return VaultAmounts.FRACTIONAL_DIGITS;
  }

  @Override
  public String format(double amount) {
    return bridge.format(amount);
  }

  @Override
  public String currencyNamePlural() {
    return bridge.currencyNamePlural();
  }

  @Override
  public String currencyNameSingular() {
    return bridge.currencyNameSingular();
  }

  // ---------------------------------------------------------------- contas

  @Override
  public boolean hasAccount(OfflinePlayer player) {
    return onMain(() -> bridge.hasAccount(player.getUniqueId()), false);
  }

  @Override
  public boolean hasAccount(String playerName) {
    return onMain(() -> resolve(playerName).map(bridge::hasAccount).orElse(false), false);
  }

  @Override
  public boolean hasAccount(OfflinePlayer player, String worldName) {
    return hasAccount(player);
  }

  @Override
  public boolean hasAccount(String playerName, String worldName) {
    return hasAccount(playerName);
  }

  @Override
  public boolean createPlayerAccount(OfflinePlayer player) {
    return onMain(() -> bridge.createAccount(player.getUniqueId(), player.getName()), false);
  }

  @Override
  public boolean createPlayerAccount(String playerName) {
    // Sem UUID não há como criar: um nome não identifica a conta.
    return onMain(
        () -> {
          Player online = Bukkit.getPlayerExact(playerName);
          return online != null && bridge.createAccount(online.getUniqueId(), online.getName());
        },
        false);
  }

  @Override
  public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
    return createPlayerAccount(player);
  }

  @Override
  public boolean createPlayerAccount(String playerName, String worldName) {
    return createPlayerAccount(playerName);
  }

  // ---------------------------------------------------------------- saldo

  @Override
  public double getBalance(OfflinePlayer player) {
    return onMain(() -> bridge.balance(player.getUniqueId()), 0.0);
  }

  @Override
  public double getBalance(String playerName) {
    return onMain(() -> resolve(playerName).map(bridge::balance).orElse(0.0), 0.0);
  }

  @Override
  public double getBalance(OfflinePlayer player, String world) {
    return getBalance(player);
  }

  @Override
  public double getBalance(String playerName, String world) {
    return getBalance(playerName);
  }

  @Override
  public boolean has(OfflinePlayer player, double amount) {
    return onMain(() -> bridge.has(player.getUniqueId(), amount), false);
  }

  @Override
  public boolean has(String playerName, double amount) {
    return onMain(() -> resolve(playerName).map(u -> bridge.has(u, amount)).orElse(false), false);
  }

  @Override
  public boolean has(OfflinePlayer player, String worldName, double amount) {
    return has(player, amount);
  }

  @Override
  public boolean has(String playerName, String worldName, double amount) {
    return has(playerName, amount);
  }

  // ---------------------------------------------------------------- movimentação

  @Override
  public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
    return onMain(() -> bridge.withdraw(player.getUniqueId(), amount), timeout(amount));
  }

  @Override
  public EconomyResponse withdrawPlayer(String playerName, double amount) {
    return onMain(
        () -> resolve(playerName).map(u -> bridge.withdraw(u, amount)).orElse(unknown(amount)),
        timeout(amount));
  }

  @Override
  public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
    return withdrawPlayer(player, amount);
  }

  @Override
  public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
    return withdrawPlayer(playerName, amount);
  }

  @Override
  public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
    return onMain(() -> bridge.deposit(player.getUniqueId(), amount), timeout(amount));
  }

  @Override
  public EconomyResponse depositPlayer(String playerName, double amount) {
    return onMain(
        () -> resolve(playerName).map(u -> bridge.deposit(u, amount)).orElse(unknown(amount)),
        timeout(amount));
  }

  @Override
  public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
    return depositPlayer(player, amount);
  }

  @Override
  public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
    return depositPlayer(playerName, amount);
  }

  // ---------------------------------------------------------------- bancos (não suportados)

  @Override
  public EconomyResponse createBank(String name, String player) {
    return noBanks();
  }

  @Override
  public EconomyResponse createBank(String name, OfflinePlayer player) {
    return noBanks();
  }

  @Override
  public EconomyResponse deleteBank(String name) {
    return noBanks();
  }

  @Override
  public EconomyResponse bankBalance(String name) {
    return noBanks();
  }

  @Override
  public EconomyResponse bankHas(String name, double amount) {
    return noBanks();
  }

  @Override
  public EconomyResponse bankWithdraw(String name, double amount) {
    return noBanks();
  }

  @Override
  public EconomyResponse bankDeposit(String name, double amount) {
    return noBanks();
  }

  @Override
  public EconomyResponse isBankOwner(String name, String playerName) {
    return noBanks();
  }

  @Override
  public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
    return noBanks();
  }

  @Override
  public EconomyResponse isBankMember(String name, String playerName) {
    return noBanks();
  }

  @Override
  public EconomyResponse isBankMember(String name, OfflinePlayer player) {
    return noBanks();
  }

  @Override
  public List<String> getBanks() {
    return List.of();
  }

  // ---------------------------------------------------------------- apoio

  /** Nome → UUID: jogador online primeiro, senão o último que entrou com o nome. */
  private Optional<UUID> resolve(String playerName) {
    if (playerName == null) {
      return Optional.empty();
    }
    Player online = Bukkit.getPlayerExact(playerName);
    if (online != null) {
      return Optional.of(online.getUniqueId());
    }
    try {
      return accounts.findUuidBlocking(playerName, timeoutMillis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception error) {
      plugin.getLogger().log(Level.WARNING, "Vault: busca de " + playerName + " falhou", error);
      return Optional.empty();
    }
  }

  private <T> T onMain(Callable<T> call, T fallback) {
    if (!plugin.isEnabled()) {
      // Referência guardada por outro plugin depois de um disable só deste: recusa, nunca finge.
      return fallback;
    }
    try {
      if (Bukkit.isPrimaryThread()) {
        return call.call();
      }
      if (!warnedAsync) {
        warnedAsync = true;
        plugin.getLogger().warning(
            "Um plugin chamou o Vault fora da main thread; a chamada é levada para a main thread"
                + " e esperada (aviso único).");
      }
      Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, call);
      try {
        return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException slow) {
        // Responder falha com a tarefa ainda na fila seria mentir: ela rodaria depois e o dinheiro
        // mudaria com o chamador achando que não. Cancelada a tempo, a falha é verdade; se ela já
        // começou, o resultado real está a instantes e é ele que volta.
        return future.cancel(false) ? fallback : future.get();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return fallback;
    } catch (Exception error) {
      plugin.getLogger().log(Level.WARNING, "Vault: chamada falhou", error);
      return fallback;
    }
  }

  private static EconomyResponse unknown(double amount) {
    return new EconomyResponse(amount, 0, ResponseType.FAILURE, VaultBridge.ERROR_NO_ACCOUNT);
  }

  private static EconomyResponse timeout(double amount) {
    return new EconomyResponse(amount, 0, ResponseType.FAILURE, VaultBridge.ERROR_DATABASE);
  }

  private static EconomyResponse noBanks() {
    return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, NO_BANKS);
  }
}
