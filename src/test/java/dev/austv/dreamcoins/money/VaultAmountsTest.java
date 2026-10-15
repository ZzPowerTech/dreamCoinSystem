package dev.austv.dreamcoins.money;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.austv.dreamcoins.config.PluginConfig;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class VaultAmountsTest {

  @Test
  void oneVaultUnitIsOneSilver() {
    assertEquals(OptionalLong.of(100), VaultAmounts.toBronzeFloor(1.0));
    assertEquals(OptionalLong.of(1250), VaultAmounts.toBronzeFloor(12.50));
    assertEquals(OptionalLong.of(1), VaultAmounts.toBronzeFloor(0.01));
    assertEquals(OptionalLong.of(100_000), VaultAmounts.toBronzeFloor(1000.0));
  }

  @Test
  void decimalsThatAreNotExactInBinaryStillConvertExactly() {
    // 0.1 + 0.2 em double é 0.30000000000000004; 0.29 * 100 é 28.999999999999996.
    assertEquals(OptionalLong.of(29), VaultAmounts.toBronzeFloor(0.29));
    assertEquals(OptionalLong.of(29), VaultAmounts.toBronzeCeil(0.29));
    assertEquals(OptionalLong.of(1_002_003), VaultAmounts.toBronzeFloor(10020.03));
  }

  /** Revisão do PR #2 (I2): preço calculado em double não pode custar 1 bronze a mais. */
  @Test
  void floatingPointNoiseIsNotChargedToThePlayer() {
    assertEquals(OptionalLong.of(30), VaultAmounts.toBronzeCeil(0.1 + 0.2));
    assertEquals(OptionalLong.of(5700), VaultAmounts.toBronzeFloor(0.57 * 100));
    assertEquals(OptionalLong.of(5700), VaultAmounts.toBronzeCeil(0.57 * 100));
    assertEquals(OptionalLong.of(3333), VaultAmounts.toBronzeFloor(100.0 / 3));
    assertEquals(OptionalLong.of(3334), VaultAmounts.toBronzeCeil(100.0 / 3));
  }

  @Test
  void repeatedSubBronzeDepositsStillCreateNothing() {
    assertEquals(OptionalLong.of(0), VaultAmounts.toBronzeFloor(0.005));
    assertEquals(OptionalLong.of(0), VaultAmounts.toBronzeFloor(0.009999));
  }

  @Test
  void depositRoundsDownWithdrawRoundsUp() {
    assertEquals(OptionalLong.of(0), VaultAmounts.toBronzeFloor(0.009));
    assertEquals(OptionalLong.of(1), VaultAmounts.toBronzeCeil(0.001));
    assertEquals(OptionalLong.of(123), VaultAmounts.toBronzeFloor(1.2399));
    assertEquals(OptionalLong.of(124), VaultAmounts.toBronzeCeil(1.2301));
  }

  @Test
  void invalidAmountsAreRejected() {
    assertEquals(OptionalLong.empty(), VaultAmounts.toBronzeFloor(-0.01));
    assertEquals(OptionalLong.empty(), VaultAmounts.toBronzeCeil(-1));
    assertEquals(OptionalLong.empty(), VaultAmounts.toBronzeFloor(Double.NaN));
    assertEquals(OptionalLong.empty(), VaultAmounts.toBronzeFloor(Double.POSITIVE_INFINITY));
    assertEquals(OptionalLong.empty(), VaultAmounts.toBronzeFloor(1e300));
  }

  @Test
  void negativeZeroIsZero() {
    assertEquals(OptionalLong.of(0), VaultAmounts.toBronzeFloor(-0.0));
  }

  /** Todo saldo até o teto precisa ir ao Vault e voltar idêntico, nos dois arredondamentos. */
  @Test
  void balancesUpToTheCeilingRoundTripThroughTheVaultDouble() {
    long ceiling = PluginConfig.MAX_BALANCE_CEILING;
    long[] samples = {ceiling, ceiling - 1, ceiling - 99, 123_456_789_012_345L, 1, 99, 100};
    for (long bronze : samples) {
      double vault = VaultAmounts.toVault(bronze);
      assertEquals(OptionalLong.of(bronze), VaultAmounts.toBronzeFloor(vault), "floor " + bronze);
      assertEquals(OptionalLong.of(bronze), VaultAmounts.toBronzeCeil(vault), "ceil " + bronze);
    }
  }

  @Test
  void toVaultIsExact() {
    assertEquals(10020.03, VaultAmounts.toVault(1_002_003));
    assertEquals(0.01, VaultAmounts.toVault(1));
    assertEquals(1000.0, VaultAmounts.toVault(100_000));
  }
}
