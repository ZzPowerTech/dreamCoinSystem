package dev.austv.dreamcoins.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void ratesMatchTheSpec() {
    assertEquals(100_000, CoinType.GOLD.bronzeValue());
    assertEquals(100, CoinType.SILVER.bronzeValue());
    assertEquals(1, CoinType.BRONZE.bronzeValue());
    // 1 ouro = 1000 prata
    assertEquals(1000, CoinType.GOLD.bronzeValue() / CoinType.SILVER.bronzeValue());
  }

  @Test
  void ofCombinesAllThreeCoins() {
    assertEquals(100_203, Money.of(1, 2, 3).totalBronze());
  }

  @Test
  void decomposesIntoComponents() {
    Money money = Money.ofBronze(1_002_003);
    assertEquals(10, money.gold());
    assertEquals(20, money.silver());
    assertEquals(3, money.bronze());
  }

  @Test
  void componentsStayInsideTheirRanges() {
    Money money = Money.ofBronze(99_999);
    assertEquals(0, money.gold());
    assertEquals(999, money.silver());
    assertEquals(99, money.bronze());
  }

  @Test
  void excessIsNormalizedUpwards() {
    // 150 bronze = 1 prata e 50 bronze; 1000 prata = 1 ouro
    assertEquals(Money.of(0, 1, 50), Money.of(0, 0, 150));
    assertEquals(Money.of(1, 0, 0), Money.of(0, 1000, 0));
  }

  @Test
  void payingSmallCoinFromBigCoinConvertsAutomatically() {
    Money after = Money.of(1, 0, 0).minus(Money.of(0, 0, 35));
    assertEquals(0, after.gold());
    assertEquals(999, after.silver());
    assertEquals(65, after.bronze());
  }

  @Test
  void totalExpressesTheWholeBalanceInOneCoin() {
    Money money = Money.ofBronze(1_002_003);
    assertEquals(10, money.total(CoinType.GOLD));
    assertEquals(10_020, money.total(CoinType.SILVER));
    assertEquals(1_002_003, money.total(CoinType.BRONZE));
  }

  @Test
  void negativeIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> Money.ofBronze(-1));
    assertThrows(IllegalArgumentException.class, () -> Money.of(0, 0, -1));
  }

  @Test
  void minusBelowZeroIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> Money.ofBronze(5).minus(Money.ofBronze(6)));
  }

  @Test
  void overflowIsAnErrorNotAWrapAround() {
    assertThrows(ArithmeticException.class, () -> Money.of(Long.MAX_VALUE, 0, 0));
    assertThrows(
        ArithmeticException.class,
        () -> Money.ofBronze(Long.MAX_VALUE).plus(Money.ofBronze(1)));
  }

  @Test
  void highestCoinOwned() {
    assertEquals(Optional.of(CoinType.GOLD), Money.of(1, 0, 0).highest());
    assertEquals(Optional.of(CoinType.SILVER), Money.of(0, 999, 99).highest());
    assertEquals(Optional.of(CoinType.BRONZE), Money.ofBronze(1).highest());
    assertEquals(Optional.empty(), Money.ZERO.highest());
  }

  @Test
  void zero() {
    assertTrue(Money.ZERO.isZero());
    assertFalse(Money.ofBronze(1).isZero());
  }

  @Test
  void aliasesResolveCaseInsensitively() {
    assertEquals(Optional.of(CoinType.GOLD), CoinType.byAlias("OURO"));
    assertEquals(Optional.of(CoinType.GOLD), CoinType.byAlias("o"));
    assertEquals(Optional.of(CoinType.SILVER), CoinType.byAlias("pratas"));
    assertEquals(Optional.of(CoinType.BRONZE), CoinType.byAlias("b"));
    assertEquals(Optional.empty(), CoinType.byAlias("cobre"));
  }
}
