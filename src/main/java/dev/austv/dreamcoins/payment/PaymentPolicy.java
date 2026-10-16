package dev.austv.dreamcoins.payment;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * As proteções do {@code /pagar}, como decisão pura: entra o pedido, sai o veredito. Sem Bukkit e
 * sem relógio próprio — o "agora" vem de quem chama, para os testes controlarem o tempo.
 *
 * <p>O saldo <b>não</b> é conferido aqui: quem decide se há dinheiro é a transferência, que débito
 * e crédito fazem juntos. Conferir antes daria uma resposta que pode estar velha no instante da
 * transferência.
 */
public final class PaymentPolicy {

  /**
   * As regras do {@code config.yml}, em bronze e milissegundos.
   *
   * @param maxAmount 0 = sem teto por pagamento
   * @param confirmAbove 0 = nunca pede confirmação
   */
  public record Rules(
      long minAmount,
      long maxAmount,
      long cooldownMillis,
      long confirmAbove,
      long confirmTimeoutMillis,
      boolean allowOffline) {}

  /** Por que o pagamento foi recusado antes de mexer em saldo. */
  public enum Refusal {
    SELF,
    BELOW_MIN,
    ABOVE_MAX,
    COOLDOWN,
    OFFLINE_NOT_ALLOWED
  }

  /** O veredito. */
  public sealed interface Decision {
    record Proceed() implements Decision {}

    record NeedsConfirmation() implements Decision {}

    /**
     * @param detail o mínimo, o máximo ou os milissegundos restantes de cooldown, conforme o motivo
     */
    record Refused(Refusal reason, long detail) implements Decision {}
  }

  private PaymentPolicy() {}

  /**
   * @param lastPaymentAt quando o pagador fez o último pagamento concluído
   * @param confirmed o pedido já é a confirmação ({@code /pagar confirmar})
   */
  public static Decision decide(
      Rules rules,
      UUID payer,
      UUID target,
      boolean targetOnline,
      long amount,
      long nowMillis,
      OptionalLong lastPaymentAt,
      boolean bypassCooldown,
      boolean bypassConfirm,
      boolean confirmed) {
    if (payer.equals(target)) {
      return new Decision.Refused(Refusal.SELF, 0);
    }
    if (amount < rules.minAmount()) {
      return new Decision.Refused(Refusal.BELOW_MIN, rules.minAmount());
    }
    if (rules.maxAmount() > 0 && amount > rules.maxAmount()) {
      return new Decision.Refused(Refusal.ABOVE_MAX, rules.maxAmount());
    }
    if (!targetOnline && !rules.allowOffline()) {
      return new Decision.Refused(Refusal.OFFLINE_NOT_ALLOWED, 0);
    }
    if (!bypassCooldown && lastPaymentAt.isPresent()) {
      long elapsed = nowMillis - lastPaymentAt.getAsLong();
      if (elapsed < rules.cooldownMillis()) {
        return new Decision.Refused(Refusal.COOLDOWN, rules.cooldownMillis() - elapsed);
      }
    }
    if (!confirmed && !bypassConfirm && rules.confirmAbove() > 0 && amount > rules.confirmAbove()) {
      return new Decision.NeedsConfirmation();
    }
    return new Decision.Proceed();
  }
}
