package dev.austv.dreamcoins;

import dev.austv.dreamcoins.account.AccountListener;
import dev.austv.dreamcoins.account.AccountService;
import dev.austv.dreamcoins.command.AdminCommand;
import dev.austv.dreamcoins.command.BalanceCommand;
import dev.austv.dreamcoins.config.Messages;
import dev.austv.dreamcoins.config.PluginConfig;
import dev.austv.dreamcoins.storage.MySqlStorage;
import dev.austv.dreamcoins.storage.SequencedExecutor;
import dev.austv.dreamcoins.storage.SqliteStorage;
import dev.austv.dreamcoins.storage.Storage;
import dev.austv.dreamcoins.storage.StorageType;
import dev.austv.dreamcoins.vault.DreamEconomy;
import dev.austv.dreamcoins.vault.VaultBridge;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Ponto de entrada do plugin. */
public final class DreamCoins extends JavaPlugin {

  private PluginConfig config;
  private Messages messages;
  private AccountService accounts;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    getConfig().options().copyDefaults(true);
    saveConfig();
    config = PluginConfig.from(getConfig(), message -> getLogger().warning(message));
    messages = new Messages(this);

    Storage storage = createStorage(config);
    try {
      storage.open();
    } catch (SQLException | RuntimeException error) {
      getLogger().log(Level.SEVERE, "Não foi possível abrir o banco; desligando.", error);
      storage.close();
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    accounts =
        new AccountService(
            storage,
            new SequencedExecutor("dreamCoins-db"),
            mainThread(),
            uuid -> Bukkit.getPlayer(uuid) != null,
            System::currentTimeMillis,
            getLogger(),
            config.startingBalance(),
            config.maxBalance());

    getServer().getPluginManager().registerEvents(
        new AccountListener(accounts, messages, getLogger()), this);
    // Reload com jogadores online: eles não passaram pelo pré-login desta instância.
    for (Player player : Bukkit.getOnlinePlayers()) {
      accounts.join(player.getUniqueId(), player.getName());
    }

    BalanceCommand balance = new BalanceCommand(accounts, messages, getLogger());
    Objects.requireNonNull(getCommand("saldo")).setExecutor(balance);
    Objects.requireNonNull(getCommand("saldo")).setTabCompleter(balance);
    AdminCommand admin = new AdminCommand(accounts, messages, getLogger(), this::reload);
    Objects.requireNonNull(getCommand("moedas")).setExecutor(admin);
    Objects.requireNonNull(getCommand("moedas")).setTabCompleter(admin);

    registerEconomy();
    scheduleTasks();
    String backend = config.storageType().name().toLowerCase(Locale.ROOT);
    getLogger().info("Economia pronta (" + backend + ").");
  }

  @Override
  public void onDisable() {
    if (accounts != null) {
      accounts.shutdown();
    }
  }

  /**
   * {@code /moedas reload}: relê config e mensagens e aplica o que dá para aplicar a quente — saldo
   * inicial e teto. Banco e intervalos das tarefas exigem reiniciar (trocar o banco com contas no
   * cache e deltas em voo seria pedir para perder dinheiro).
   *
   * @return {@code true} se alguma mudança só vale depois de reiniciar
   */
  public boolean reload() {
    reloadConfig();
    PluginConfig fresh = PluginConfig.from(getConfig(), message -> getLogger().warning(message));
    messages.reload();
    // Saldo inicial e teto valem a quente mesmo quando outra parte pede reinício; o campo config
    // só é trocado sem reinício porque guarda o que as tarefas e o Vault estão usando de fato.
    accounts.configure(fresh.startingBalance(), fresh.maxBalance());
    boolean needsRestart =
        fresh.storageType() != config.storageType()
            || !fresh.sqliteFile().equals(config.sqliteFile())
            || !fresh.mysql().equals(config.mysql())
            || fresh.flushIntervalSeconds() != config.flushIntervalSeconds()
            || fresh.resyncIntervalSeconds() != config.resyncIntervalSeconds()
            || fresh.vaultTimeoutMillis() != config.vaultTimeoutMillis();
    if (!needsRestart) {
      config = fresh;
    }
    return needsRestart;
  }

  public AccountService accounts() {
    return accounts;
  }

  public Messages messages() {
    return messages;
  }

  private Storage createStorage(PluginConfig config) {
    if (config.storageType() == StorageType.MYSQL) {
      return new MySqlStorage(config.mysql());
    }
    return new SqliteStorage(getDataFolder().toPath().resolve(config.sqliteFile()));
  }

  /**
   * Registra a economia com prioridade máxima. A conferência roda no primeiro tick, depois de todo
   * plugin ter subido: o Essentials (ou outro) pode registrar a dele depois de nós, e um provedor
   * concorrente vencendo significaria lojas cobrando numa moeda que este plugin não vê.
   */
  private void registerEconomy() {
    VaultBridge bridge =
        new VaultBridge(
            accounts, () -> messages.formatter(), config.vaultTimeoutMillis(), getLogger());
    DreamEconomy economy =
        new DreamEconomy(this, accounts, bridge, config.vaultTimeoutMillis());
    getServer()
        .getServicesManager()
        .register(Economy.class, economy, this, ServicePriority.Highest);
    Bukkit.getScheduler()
        .runTask(
            this,
            () -> {
              RegisteredServiceProvider<Economy> active =
                  getServer().getServicesManager().getRegistration(Economy.class);
              if (active != null && active.getProvider() != economy) {
                getLogger().severe(
                    "Outro provedor de economia está ativo no Vault: "
                        + active.getPlugin().getName()
                        + " ("
                        + active.getProvider().getName()
                        + "). Lojas e leilões NÃO vão usar as moedas do dreamCoinSystem;"
                        + " desative a economia daquele plugin.");
              }
            });
  }

  private void scheduleTasks() {
    long flushTicks = config.flushIntervalSeconds() * 20L;
    Bukkit.getScheduler().runTaskTimer(this, accounts::flushAll, flushTicks, flushTicks);
    Bukkit.getScheduler().runTaskTimer(this, accounts::housekeeping, 1200L, 1200L);
    // Com SQLite só este servidor escreve: o cache é a verdade e o resync seria leitura inútil.
    if (config.storageType() == StorageType.MYSQL) {
      long resyncTicks = config.resyncIntervalSeconds() * 20L;
      Bukkit.getScheduler().runTaskTimer(this, accounts::resyncOnline, resyncTicks, resyncTicks);
    }
  }

  /**
   * A main thread como {@link Executor}. Com o plugin desligado a tarefa é descartada: no {@code
   * onDisable} o scheduler já não aceita nada, e o desligamento espera a gravação diretamente.
   */
  private Executor mainThread() {
    return task -> {
      if (!isEnabled()) {
        return;
      }
      try {
        Bukkit.getScheduler().runTask(this, task);
      } catch (RuntimeException rejected) {
        getLogger().log(Level.FINE, "Tarefa descartada no desligamento", rejected);
      }
    };
  }
}
