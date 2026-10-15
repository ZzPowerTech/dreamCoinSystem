package dev.austv.dreamcoins.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.austv.dreamcoins.money.MoneyFormatter;
import java.util.OptionalLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class PlaceholderResolverTest {

  private final PlaceholderResolver resolver =
      new PlaceholderResolver(MoneyFormatter::defaults, () -> "nenhuma");

  /** Saldo de exemplo do spec: 1.002.003 bronze = 10 ouro, 20 prata e 3 bronze. */
  private static final OptionalLong BALANCE = OptionalLong.of(1_002_003);

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "balance               | 10 ouro, 20 prata e 3 bronze",
        "balance_short         | 10o 20p 3b",
        "gold                  | 10",
        "silver                | 20",
        "bronze                | 3",
        "ouro                  | 10",
        "prata                 | 20",
        "total_gold            | 10",
        "total_silver          | 10020",
        "total_bronze          | 1002003",
        "total_bronze_formatted| 1.002.003",
        "total_silver_formatted| 10.020",
        "total_prata_formatted | 10.020",
        "balance_vault         | 10020.03",
        "highest               | ouro",
        "has_10_ouro           | true",
        "has_10_ouro_20_prata_4_bronze | false",
        "has_10o_20p_3b        | true",
        "HAS_1_OURO            | true",
      })
  void exampleBalance(String params, String expected) {
    assertEquals(expected, resolver.resolve(params.trim(), BALANCE));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "balance        | 0 bronze",
        "balance_short  | 0b",
        "gold           | 0",
        "total_bronze   | 0",
        "balance_vault  | 0.00",
        "highest        | nenhuma",
        "has_1_bronze   | false",
      })
  void accountNotLoadedReadsAsZero(String params, String expected) {
    assertEquals(expected, resolver.resolve(params.trim(), OptionalLong.empty()));
  }

  @Test
  void vaultValueKeepsTwoDecimals() {
    assertEquals("0.01", resolver.resolve("balance_vault", OptionalLong.of(1)));
    assertEquals("1000.00", resolver.resolve("balance_vault", OptionalLong.of(100_000)));
    assertEquals(
        "9999999999999.99", resolver.resolve("balance_vault", OptionalLong.of(999_999_999_999_999L)));
  }

  @Test
  void highestFollowsTheBalance() {
    assertEquals("prata", resolver.resolve("highest", OptionalLong.of(99_999)));
    assertEquals("bronze", resolver.resolve("highest", OptionalLong.of(1)));
  }

  @Test
  void unknownPlaceholdersReturnNullForPlaceholderApiToShowThemRaw() {
    assertNull(resolver.resolve("nope", BALANCE));
    assertNull(resolver.resolve("total_cobre", BALANCE));
    assertNull(resolver.resolve("has_3_cobre", BALANCE));
    assertNull(resolver.resolve("has_", BALANCE));
    assertNull(resolver.resolve("cobre", BALANCE));
  }
}
