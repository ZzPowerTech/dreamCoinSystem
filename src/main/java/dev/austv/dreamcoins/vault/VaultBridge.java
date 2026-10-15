package dev.austv.dreamcoins.vault;

import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import dev.austv.dreamcoins.account.AccountService;
import dev.austv.dreamcoins.account.DatabaseUnavailableException;
import dev.austv.dreamcoins.account.OutcomeUnknownException;
import dev.austv.dreamcoins.money.CoinType;
import dev.austv.dreamcoins.money.Money;
import dev.austv.dreamcoins.money.MoneyFormatter;
import dev.austv.dreamcoins.money.VaultAmounts;
import dev.austv.dreamcoins.storage.GuardedResult;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;

/**
 * A economia do Vault por UUID, sem Bukkit — o {@link DreamEconomy} só traduz jogador e nome para
 * UUID e garante a main thread.
 *
 * <p><b>Chamado só na main thread.</b> Conta no cache vai pelo ledger, na hora. Conta fora dele vai
 * ao banco e <b>espera</b>, porque a API do Vault é síncrona e quem chama precisa da resposta — a
 * loja vai entregar o item ou não. O banco é consultado pela mesma fila de sempre, então reserva,
 * lápide e órfãos continuam valendo para essas escritas.
 *
 * <p>Arredondamento do {@link VaultAmounts}: depósito para baixo, saque e {@code has} para cima.
 */
public final class VaultBridge {

  static final String ERROR_INVALID = "Valor inválido.";
  static final String ERROR_INSUFFICIENT = "Saldo insuficiente.";
  static final String ERROR_MAX = "Saldo máximo atingido.";
  static final String ERROR_NO_ACCOUNT = "Conta inexistente.";
  static final String ERROR_BUSY = "Conta ocupada; tente de novo.";
  static final String ERROR_DATABASE = "Banco de dados indisponível.";

  private final AccountService accounts;
  private final Supplier<MoneyFormatter> formatter;
  private final long timeoutMillis;
  private final Logger logger;

  public VaultBridge(
      AccountService accounts,
      Supplier<MoneyFormatter> formatter,
      long timeoutMillis,
      Logger logger) {
    this.accounts = accounts;
    this.formatter = formatter;
    this.timeoutMillis = timeoutMillis;
    this.logger = logger;
  }

  // ---------------------------------------------------------------- leitura

  public boolean hasAccount(UUID uuid) {
    return currentBalance(uuid, "hasAccount").isPresent();
  }

  /** Saldo em unidades do Vault; 0 se a conta não existe ou o banco não respondeu. */
  public double balance(UUID uuid) {
    return VaultAmounts.toVault(currentBalance(uuid, "getBalance").orElse(0));
  }

  /** Arredonda para cima, como um saque: "tem 0.011?" só se tiver 2 bronze. */
  public boolean has(UUID uuid, double amount) {
    OptionalLong balance = currentBalance(uuid, "has");
    if (balance.isEmpty()) {
      return false;
    }
    if (amount <= 0) {
      return true;
    }
    OptionalLong needed = VaultAmounts.toBronzeCeil(amount);
    return needed.isPresent() && balance.getAsLong() >= needed.getAsLong();
  }

  // ---------------------------------------------------------------- escrita

  public EconomyResponse deposit(UUID uuid, double amount) {
    if (!Double.isFinite(amount) || amount < 0) {
      return failure(amount, uuid, ERROR_INVALID);
    }
    OptionalLong bronze = VaultAmounts.toBronzeFloor(amount);
    if (bronze.isEmpty()) {
      return failure(amount, uuid, ERROR_INVALID);
    }
    return apply(uuid, bronze.getAsLong(), amount);
  }

  public EconomyResponse withdraw(UUID uuid, double amount) {
    if (!Double.isFinite(amount) || amount < 0) {
      return failure(amount, uuid, ERROR_INVALID);
    }
    OptionalLong bronze = VaultAmounts.toBronzeCeil(amount);
    if (bronze.isEmpty()) {
      return failure(amount, uuid, ERROR_INVALID);
    }
    return apply(uuid, -bronze.getAsLong(), amount);
  }

  /**
   * Cria a conta com o saldo inicial se faltar. Não conta como login.
   *
   * @return {@code true} se a conta foi criada agora — {@code false} se já existia ou houve erro
   */
  public boolean createAccount(UUID uuid, String name) {
    try {
      return accounts.ensureAccountBlocking(uuid, name, timeoutMillis);
    } catch (Exception error) {
      logDatabase("createPlayerAccount", uuid, error);
      return false;
    }
  }

  private EconomyResponse apply(UUID uuid, long delta, double requested) {
    if (accounts.isLoaded(uuid)) {
      Outcome outcome =
          delta >= 0 ? accounts.deposit(uuid, delta) : accounts.withdraw(uuid, -delta);
      return response(outcome, delta, accounts.balance(uuid).orElse(0), requested);
    }
    try {
      GuardedResult result = accounts.applyOfflineBlocking(uuid, delta, timeoutMillis);
      return response(result.outcome(), delta, result.balance(), requested);
    } catch (OutcomeUnknownException unknown) {
      // O único caso em que a falha pode não ser verdade: a escrita começou e ainda pode gravar.
      logger.severe(
          "Vault: alteração de "
              + delta
              + " bronze na conta offline "
              + uuid
              + " tem resultado DESCONHECIDO ("
              + unknown.getMessage()
              + "). Respondi falha, mas ela ainda pode ser aplicada — confira o saldo.");
      return new EconomyResponse(requested, 0, ResponseType.FAILURE, ERROR_DATABASE);
    } catch (DatabaseUnavailableException unavailable) {
      // Garantia do AccountService: nada foi alterado, então a falha é verdadeira.
      logger.warning(
          "Vault: alteração de "
              + delta
              + " bronze na conta offline "
              + uuid
              + " recusada sem efeito ("
              + unavailable.getMessage()
              + ").");
      return new EconomyResponse(requested, 0, ResponseType.FAILURE, ERROR_DATABASE);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new EconomyResponse(requested, 0, ResponseType.FAILURE, ERROR_DATABASE);
    } catch (ExecutionException error) {
      logDatabase("alteração de saldo", uuid, error);
      return new EconomyResponse(requested, 0, ResponseType.FAILURE, ERROR_DATABASE);
    }
  }

  private static EconomyResponse response(
      Outcome outcome, long delta, long balanceBronze, double requested) {
    double balance = VaultAmounts.toVault(balanceBronze);
    return switch (outcome) {
      case OK ->
          new EconomyResponse(
              VaultAmounts.toVault(Math.abs(delta)), balance, ResponseType.SUCCESS, null);
      case INSUFFICIENT ->
          new EconomyResponse(requested, balance, ResponseType.FAILURE, ERROR_INSUFFICIENT);
      case EXCEEDS_MAX ->
          new EconomyResponse(requested, balance, ResponseType.FAILURE, ERROR_MAX);
      case NO_ACCOUNT ->
          new EconomyResponse(requested, balance, ResponseType.FAILURE, ERROR_NO_ACCOUNT);
      case BUSY -> new EconomyResponse(requested, balance, ResponseType.FAILURE, ERROR_BUSY);
      case UNAVAILABLE ->
          new EconomyResponse(requested, balance, ResponseType.FAILURE, ERROR_DATABASE);
    };
  }

  private EconomyResponse failure(double requested, UUID uuid, String error) {
    double balance = VaultAmounts.toVault(accounts.balance(uuid).orElse(0));
    return new EconomyResponse(Double.isFinite(requested) ? requested : 0, balance,
        ResponseType.FAILURE, error);
  }

  private OptionalLong currentBalance(UUID uuid, String operation) {
    try {
      return accounts.balanceBlocking(uuid, timeoutMillis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return OptionalLong.empty();
    } catch (ExecutionException | DatabaseUnavailableException | OutcomeUnknownException error) {
      logDatabase(operation, uuid, error);
      return OptionalLong.empty();
    }
  }

  private void logDatabase(String operation, UUID uuid, Exception error) {
    logger.log(Level.WARNING, "Vault: " + operation + " de " + uuid + " falhou no banco", error);
  }

  // ---------------------------------------------------------------- formatação

  /** {@code 12.50} → {@code 12 prata e 50 bronze}. Fração de bronze é descartada. */
  public String format(double amount) {
    if (!Double.isFinite(amount)) {
      return formatter.get().format(Money.ZERO);
    }
    long bronze = VaultAmounts.toBronzeFloor(Math.abs(amount)).orElse(0);
    String text = formatter.get().format(Money.ofBronze(bronze));
    return amount < 0 && bronze > 0 ? "-" + text : text;
  }

  /** A unidade do Vault é a prata. */
  public String currencyNameSingular() {
    return formatter.get().name(CoinType.SILVER, 1);
  }

  public String currencyNamePlural() {
    return formatter.get().name(CoinType.SILVER, 2);
  }
}
