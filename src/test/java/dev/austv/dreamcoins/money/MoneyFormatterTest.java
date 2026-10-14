package dev.austv.dreamcoins.money;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MoneyFormatterTest {

  private final MoneyFormatter formatter = MoneyFormatter.defaults();

  @Test
  void allThreeCoins() {
    assertEquals("1 ouro, 2 prata e 3 bronze", formatter.format(Money.of(1, 2, 3)));
  }

  @Test
  void zerosAreOmitted() {
    assertEquals("999 prata e 65 bronze", formatter.format(Money.of(0, 999, 65)));
    assertEquals("1 ouro e 3 bronze", formatter.format(Money.of(1, 0, 3)));
    assertEquals("1 ouro", formatter.format(Money.of(1, 0, 0)));
    assertEquals("2 bronze", formatter.format(Money.ofBronze(2)));
  }

  @Test
  void zeroBalance() {
    assertEquals("0 bronze", formatter.format(Money.ZERO));
    assertEquals("0b", formatter.formatShort(Money.ZERO));
  }

  @Test
  void shortForm() {
    assertEquals("10o 20p 3b", formatter.formatShort(Money.ofBronze(1_002_003)));
    assertEquals("5p", formatter.formatShort(Money.of(0, 5, 0)));
  }

  @Test
  void thousandsSeparator() {
    assertEquals("1.002.003", formatter.number(1_002_003));
    assertEquals("999", formatter.number(999));
    assertEquals("1.000", formatter.number(1000));
    assertEquals("0", formatter.number(0));
    assertEquals("12.345 ouro", formatter.format(Money.of(12_345, 0, 0)));
  }

  @Test
  void singularAndPluralFromConfig() {
    MoneyFormatter custom =
        new MoneyFormatter(
            Map.of(
                CoinType.GOLD, new MoneyFormatter.CoinName("ouro", "ouros", "o"),
                CoinType.SILVER, new MoneyFormatter.CoinName("prata", "pratas", "p"),
                CoinType.BRONZE, new MoneyFormatter.CoinName("bronze", "bronzes", "b")),
            ", ",
            " e ",
            ".");
    assertEquals("1 ouro e 2 pratas", custom.format(Money.of(1, 2, 0)));
    assertEquals("0 bronzes", custom.format(Money.ZERO));
  }
}
