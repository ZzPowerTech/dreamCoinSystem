package dev.austv.dreamcoins.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.austv.dreamcoins.money.Money;
import dev.austv.dreamcoins.money.MoneyParser.ParseError;
import dev.austv.dreamcoins.money.MoneyParser.ParseResult;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AmountArgsTest {

  private static String[] args(String line) {
    return line.split(" ");
  }

  @Test
  void parsesFromTheGivenIndex() {
    ParseResult result = AmountArgs.parse(args("give whitefox 1 ouro 35 bronze"), 2, false);
    assertEquals(
        Money.of(1, 0, 35), assertInstanceOf(ParseResult.Success.class, result).money());
  }

  @Test
  void zeroOnlyWhenAllowed() {
    assertEquals(
        Money.ZERO,
        assertInstanceOf(ParseResult.Success.class, AmountArgs.parse(args("set fox 0"), 2, true))
            .money());
    assertEquals(
        ParseError.NOT_POSITIVE,
        assertInstanceOf(ParseResult.Failure.class, AmountArgs.parse(args("give fox 0"), 2, false))
            .error());
  }

  @Test
  void zeroWithACoinIsAlsoAllowedForSet() {
    assertEquals(
        Money.ZERO,
        assertInstanceOf(
                ParseResult.Success.class, AmountArgs.parse(args("set fox 0 ouro"), 2, true))
            .money());
  }

  @Test
  void nothingAfterThePlayerIsEmpty() {
    assertEquals(
        ParseError.EMPTY,
        assertInstanceOf(ParseResult.Failure.class, AmountArgs.parse(args("give fox"), 2, false))
            .error());
  }

  @Test
  void everyParseErrorHasAMessageKey() {
    Set<String> keys = new java.util.HashSet<>();
    for (ParseError error : EnumSet.allOf(ParseError.class)) {
      keys.add(AmountArgs.messageKey(error));
    }
    assertEquals(ParseError.values().length, keys.size());
    assertEquals("amount-errors.unknown-coin", AmountArgs.messageKey(ParseError.UNKNOWN_COIN));
  }
}
