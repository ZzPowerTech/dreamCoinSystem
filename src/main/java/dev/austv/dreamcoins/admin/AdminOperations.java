package dev.austv.dreamcoins.admin;

import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import dev.austv.dreamcoins.account.AccountService;
import dev.austv.dreamcoins.storage.GuardedResult;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * As operações do {@code /moedas}, por UUID e sem Bukkit. Tudo completa na main thread.
 *
 * <p>Conta no cache vai pelo cache, na hora. Conta fora dele vai pelo {@code applyOffline} — o
 * mesmo caminho do Vault, com reserva para retirada —, porque o admin mexe em quem está offline
 * tanto quanto em quem está online.
 *
 * <p>O {@code set} de conta offline <b>não grava valor absoluto</b>: a regra do projeto é delta,
 * para dois servidores no mesmo banco somarem em vez de se apagar. Ele lê o saldo, aplica a
 * diferença e confere o resultado; se outra escrita entrou no meio, o saldo final não é o pedido, e
 * {@link Result#drifted} avisa o admin com o valor real em vez de dizer "pronto".
 */
public final class AdminOperations {

  /**
   * O que aconteceu e o saldo depois.
   *
   * @param drifted o {@code set} foi aplicado, mas outra escrita entrou entre a leitura e a
   *     gravação e o saldo final é diferente do pedido
   */
  public record Result(Outcome outcome, long balance, boolean drifted) {}

  private final AccountService accounts;

  public AdminOperations(AccountService accounts) {
    this.accounts = accounts;
  }

  /** @param amount em bronze, maior que zero */
  public CompletableFuture<Result> give(UUID uuid, long amount) {
    return change(uuid, amount);
  }

  /** @param amount em bronze, maior que zero; acima do saldo é recusado, nunca zera em silêncio */
  public CompletableFuture<Result> take(UUID uuid, long amount) {
    return change(uuid, -amount);
  }

  /** @param target em bronze, zero ou mais */
  public CompletableFuture<Result> set(UUID uuid, long target) {
    if (target > accounts.maxBalance()) {
      return completed(Outcome.EXCEEDS_MAX, accounts.balance(uuid).orElse(0));
    }
    if (accounts.isLoaded(uuid)) {
      Outcome outcome = accounts.set(uuid, target);
      return completed(outcome, accounts.balance(uuid).orElse(0));
    }
    return accounts
        .readBalance(uuid)
        .thenCompose(
            current -> {
              if (current.isEmpty()) {
                return completed(Outcome.NO_ACCOUNT, 0);
              }
              long delta = target - current.getAsLong();
              return accounts
                  .applyOffline(uuid, delta)
                  .thenApply(
                      result ->
                          new Result(
                              result.outcome(),
                              result.balance(),
                              result.outcome() == Outcome.OK && result.balance() != target));
            });
  }

  private CompletableFuture<Result> change(UUID uuid, long delta) {
    return accounts.applyOffline(uuid, delta).thenApply(AdminOperations::toResult);
  }

  private static Result toResult(GuardedResult result) {
    return new Result(result.outcome(), result.balance(), false);
  }

  private static CompletableFuture<Result> completed(Outcome outcome, long balance) {
    return CompletableFuture.completedFuture(new Result(outcome, balance, false));
  }
}
