package dev.austv.dreamcoins.payment;

import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import dev.austv.dreamcoins.account.AccountService;
import dev.austv.dreamcoins.payment.PaymentPolicy.Decision;
import dev.austv.dreamcoins.payment.PaymentPolicy.Rules;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * O {@code /pagar}: aplica a {@link PaymentPolicy}, guarda o pedido à espera de confirmação e move
 * o dinheiro. Sem Bukkit; <b>main thread</b>, e tudo completa nela.
 *
 * <p><b>Alvo no cache:</b> {@link AccountService#transfer} — débito e crédito juntos, ou nenhum.
 *
 * <p><b>Alvo fora do cache:</b> o débito do pagador sai do cache na hora e é gravado já
 * ({@link AccountService#flushNow}); o crédito do alvo vai ao banco pelo {@code applyOffline}. Se o
 * crédito falhar — conta sumiu, teto, banco indisponível —, o pagador recebe o <b>estorno</b>. A
 * ordem na fila do banco é FIFO, então o débito chega antes do crédito: um crash entre os dois
 * perde o pagamento em vez de duplicá-lo.
 */
public final class PaymentService {

  /** O resultado de um pedido de pagamento. */
  public enum Status {
    PAID,
    NEEDS_CONFIRMATION,
    SELF,
    BELOW_MIN,
    ABOVE_MAX,
    COOLDOWN,
    OFFLINE_NOT_ALLOWED,
    INSUFFICIENT,
    RECEIVER_FULL,
    UNKNOWN_TARGET,
    PAYER_NOT_LOADED,
    BUSY,
    UNAVAILABLE,
    NO_PENDING
  }

  /**
   * @param detail o mínimo/máximo, os ms restantes de cooldown ou o prazo da confirmação em ms,
   *     conforme o {@code status}
   */
  public record Result(
      Status status, long amount, long payerBalance, long detail, UUID target, String targetName) {}

  private record Pending(UUID target, String targetName, long amount, long expiresAtMillis) {}

  private final AccountService accounts;
  private final Supplier<Rules> rules;
  private final LongSupplier clock;
  private final Logger logger;

  private final Map<UUID, Long> lastPayment = new HashMap<>();
  private final Map<UUID, Pending> pending = new HashMap<>();

  public PaymentService(
      AccountService accounts, Supplier<Rules> rules, LongSupplier clock, Logger logger) {
    this.accounts = accounts;
    this.rules = rules;
    this.clock = clock;
    this.logger = logger;
  }

  /** {@code /pagar <alvo> <quantia>}. */
  public CompletableFuture<Result> request(
      UUID payer,
      UUID target,
      String targetName,
      long amount,
      boolean bypassCooldown,
      boolean bypassConfirm) {
    return decideAndRun(payer, target, targetName, amount, bypassCooldown, bypassConfirm, false);
  }

  /** {@code /pagar confirmar}: executa o último pedido do pagador, se ainda estiver no prazo. */
  public CompletableFuture<Result> confirm(UUID payer, boolean bypassCooldown) {
    Pending request = pending.remove(payer);
    if (request == null || clock.getAsLong() > request.expiresAtMillis()) {
      return done(Status.NO_PENDING, 0, payer, 0, null, null);
    }
    return decideAndRun(
        payer,
        request.target(),
        request.targetName(),
        request.amount(),
        bypassCooldown,
        true,
        true)
        .thenApply(
            result -> {
              // Recusa temporária não apaga o pedido: espera-se o cooldown e confirma-se de novo.
              if (result.status() == Status.COOLDOWN) {
                pending.putIfAbsent(payer, request);
              }
              return result;
            });
  }

  /** O jogador saiu: o pedido à espera de confirmação morre com a sessão. */
  public void forget(UUID payer) {
    pending.remove(payer);
  }

  private CompletableFuture<Result> decideAndRun(
      UUID payer,
      UUID target,
      String targetName,
      long amount,
      boolean bypassCooldown,
      boolean bypassConfirm,
      boolean confirmed) {
    long now = clock.getAsLong();
    Rules current = rules.get();
    Decision decision =
        PaymentPolicy.decide(
            current,
            payer,
            target,
            // A regra é sobre o jogador estar online de verdade; estar no cache (saindo, devendo
            // flush) ou fora dele (entrando) só decide o caminho do dinheiro, mais abaixo.
            accounts.isOnline(target),
            amount,
            now,
            lastPaymentOf(payer),
            bypassCooldown,
            bypassConfirm,
            confirmed);
    return switch (decision) {
      case Decision.Refused refused ->
          done(status(refused.reason()), amount, payer, refused.detail(), target, targetName);
      case Decision.NeedsConfirmation ignored -> {
        long timeout = current.confirmTimeoutMillis();
        pending.put(payer, new Pending(target, targetName, amount, now + timeout));
        yield done(Status.NEEDS_CONFIRMATION, amount, payer, timeout, target, targetName);
      }
      case Decision.Proceed ignored -> execute(payer, target, targetName, amount, now);
    };
  }

  private CompletableFuture<Result> execute(
      UUID payer, UUID target, String targetName, long amount, long now) {
    if (!accounts.isLoaded(payer)) {
      return done(Status.PAYER_NOT_LOADED, amount, payer, 0, target, targetName);
    }
    // O cooldown começa no pedido, não na conclusão: um pagamento offline leva um ida-e-volta ao
    // banco, e dois cliques nesse meio passariam os dois.
    Long previous = lastPayment.put(payer, now);
    if (accounts.isLoaded(target)) {
      Status status = status(accounts.transfer(payer, target, amount));
      return finish(status, payer, previous, now, amount, target, targetName);
    }
    return accounts
        .transferToOffline(payer, target, amount)
        .handle(
            (outcome, error) -> {
              Status status = error != null ? Status.UNAVAILABLE : status(outcome);
              if (status != Status.PAID) {
                restoreCooldown(payer, previous, now);
              }
              return result(status, amount, payer, 0, target, targetName);
            });
  }

  private CompletableFuture<Result> finish(
      Status status,
      UUID payer,
      Long previous,
      long now,
      long amount,
      UUID target,
      String targetName) {
    if (status != Status.PAID) {
      restoreCooldown(payer, previous, now);
    }
    return done(status, amount, payer, 0, target, targetName);
  }

  /**
   * Desfaz o cooldown de um pagamento que falhou — só se ninguém o mudou depois. Um pagamento
   * offline lento que falhe depois de outro pagamento do mesmo jogador não pode apagar o cooldown
   * daquele outro.
   */
  private void restoreCooldown(UUID payer, Long previous, long markedAt) {
    Long current = lastPayment.get(payer);
    if (current == null || current != markedAt) {
      return;
    }
    if (previous == null) {
      lastPayment.remove(payer);
    } else {
      lastPayment.put(payer, previous);
    }
  }

  private OptionalLong lastPaymentOf(UUID payer) {
    Long at = lastPayment.get(payer);
    return at == null ? OptionalLong.empty() : OptionalLong.of(at);
  }

  private static Status status(Outcome outcome) {
    return switch (outcome) {
      case OK -> Status.PAID;
      case INSUFFICIENT -> Status.INSUFFICIENT;
      case EXCEEDS_MAX -> Status.RECEIVER_FULL;
      case NO_ACCOUNT -> Status.UNKNOWN_TARGET;
      case BUSY -> Status.BUSY;
      default -> Status.UNAVAILABLE;
    };
  }

  private static Status status(PaymentPolicy.Refusal refusal) {
    return switch (refusal) {
      case SELF -> Status.SELF;
      case BELOW_MIN -> Status.BELOW_MIN;
      case ABOVE_MAX -> Status.ABOVE_MAX;
      case COOLDOWN -> Status.COOLDOWN;
      case OFFLINE_NOT_ALLOWED -> Status.OFFLINE_NOT_ALLOWED;
    };
  }

  private Result result(
      Status status, long amount, UUID payer, long detail, UUID target, String targetName) {
    return new Result(
        status, amount, accounts.balance(payer).orElse(0), detail, target, targetName);
  }

  private CompletableFuture<Result> done(
      Status status, long amount, UUID payer, long detail, UUID target, String targetName) {
    return CompletableFuture.completedFuture(
        result(status, amount, payer, detail, target, targetName));
  }
}
