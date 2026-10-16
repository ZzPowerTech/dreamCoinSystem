package dev.austv.dreamcoins.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.austv.dreamcoins.payment.PaymentPolicy.Decision;
import dev.austv.dreamcoins.payment.PaymentPolicy.Refusal;
import dev.austv.dreamcoins.payment.PaymentPolicy.Rules;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentPolicyTest {

  private static final Rules RULES = new Rules(1, 0, 3_000, 1_000_000, 30_000, true);
  private final UUID payer = UUID.randomUUID();
  private final UUID target = UUID.randomUUID();

  private Decision decide(Rules rules, long amount, OptionalLong last, boolean... flags) {
    boolean cached = flags.length < 1 || flags[0];
    boolean bypassCooldown = flags.length > 1 && flags[1];
    boolean bypassConfirm = flags.length > 2 && flags[2];
    boolean confirmed = flags.length > 3 && flags[3];
    return PaymentPolicy.decide(
        rules, payer, target, cached, amount, 10_000, last, bypassCooldown, bypassConfirm,
        confirmed);
  }

  private Decision decide(long amount) {
    return decide(RULES, amount, OptionalLong.empty());
  }

  private static Refusal refusal(Decision decision) {
    return assertInstanceOf(Decision.Refused.class, decision).reason();
  }

  @Test
  void ordinaryPaymentProceeds() {
    assertInstanceOf(Decision.Proceed.class, decide(201));
  }

  @Test
  void cannotPaySelf() {
    Decision decision =
        PaymentPolicy.decide(
            RULES, payer, payer, true, 100, 0, OptionalLong.empty(), true, true, true);
    assertEquals(Refusal.SELF, refusal(decision));
  }

  @Test
  void minimumAndMaximum() {
    Rules rules = new Rules(100, 500, 0, 0, 0, true);
    assertEquals(Refusal.BELOW_MIN, refusal(decide(rules, 99, OptionalLong.empty())));
    assertInstanceOf(Decision.Proceed.class, decide(rules, 100, OptionalLong.empty()));
    assertInstanceOf(Decision.Proceed.class, decide(rules, 500, OptionalLong.empty()));
    Decision above = decide(rules, 501, OptionalLong.empty());
    assertEquals(Refusal.ABOVE_MAX, refusal(above));
    assertEquals(500, ((Decision.Refused) above).detail());
  }

  @Test
  void zeroMaximumMeansNoLimit() {
    assertInstanceOf(
        Decision.Proceed.class,
        decide(new Rules(1, 0, 0, 0, 0, true), Long.MAX_VALUE / 2, OptionalLong.empty()));
  }

  @Test
  void cooldownReportsTheRemainingTime() {
    Decision decision = decide(RULES, 10, OptionalLong.of(9_000)); // 1 s atrás, cooldown 3 s
    assertEquals(Refusal.COOLDOWN, refusal(decision));
    assertEquals(2_000, ((Decision.Refused) decision).detail());
    assertInstanceOf(Decision.Proceed.class, decide(RULES, 10, OptionalLong.of(7_000)));
  }

  @Test
  void cooldownBypass() {
    assertInstanceOf(
        Decision.Proceed.class, decide(RULES, 10, OptionalLong.of(9_999), true, true));
  }

  @Test
  void confirmationAboveTheThreshold() {
    assertInstanceOf(Decision.Proceed.class, decide(1_000_000));
    assertInstanceOf(Decision.NeedsConfirmation.class, decide(1_000_001));
  }

  @Test
  void confirmationBypassAndAlreadyConfirmed() {
    assertInstanceOf(
        Decision.Proceed.class,
        decide(RULES, 1_000_001, OptionalLong.empty(), true, false, true));
    assertInstanceOf(
        Decision.Proceed.class,
        decide(RULES, 1_000_001, OptionalLong.empty(), true, false, false, true));
  }

  @Test
  void zeroThresholdNeverAsks() {
    assertInstanceOf(
        Decision.Proceed.class,
        decide(new Rules(1, 0, 0, 0, 0, true), 999_999_999, OptionalLong.empty()));
  }

  @Test
  void offlineTargetOnlyWhenAllowed() {
    Rules noOffline = new Rules(1, 0, 0, 0, 0, false);
    assertEquals(
        Refusal.OFFLINE_NOT_ALLOWED, refusal(decide(noOffline, 10, OptionalLong.empty(), false)));
    assertInstanceOf(Decision.Proceed.class, decide(RULES, 10, OptionalLong.empty(), false));
  }

  @Test
  void selfCheckComesFirst() {
    Decision decision =
        PaymentPolicy.decide(
            new Rules(100, 0, 0, 0, 0, false), payer, payer, false, 1, 0,
            OptionalLong.empty(), false, false, false);
    assertEquals(Refusal.SELF, refusal(decision));
  }
}
