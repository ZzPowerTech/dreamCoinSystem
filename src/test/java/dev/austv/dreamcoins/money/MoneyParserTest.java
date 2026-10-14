package dev.austv.dreamcoins.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.austv.dreamcoins.money.MoneyParser.ParseError;
import dev.austv.dreamcoins.money.MoneyParser.ParseResult;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class MoneyParserTest {

  private static Money ok(String input) {
    ParseResult result = MoneyParser.parse(List.of(input.trim().split("\\s+")));
    return assertInstanceOf(ParseResult.Success.class, result, input).money();
  }

  private static ParseResult.Failure fail(String input) {
    List<String> args = input.isBlank() ? List.of() : List.of(input.trim().split("\\s+"));
    return assertInstanceOf(ParseResult.Failure.class, MoneyParser.parse(args), input);
  }

  /** Os exemplos exatos do pedido. */
  @ParameterizedTest
  @CsvSource({
    "2 prata 1 bronze,            201",
    "1 ouro 35 bronze,            100035",
    "1 ouro,                      100000",
    "1 ouro 2 prata 3 bronze,     100203",
    "2 bronze,                    2",
  })
  void requestExamples(String input, long expectedBronze) {
    assertEquals(expectedBronze, ok(input).totalBronze());
  }

  @Test
  void orderDoesNotMatter() {
    assertEquals(ok("1 ouro 2 prata 3 bronze"), ok("3 bronze 1 ouro 2 prata"));
  }

  @Test
  void compactFormAndMixing() {
    assertEquals(201, ok("2p 1b").totalBronze());
    assertEquals(100_035, ok("1o 35 bronze").totalBronze());
    assertEquals(100_000, ok("1ouro").totalBronze());
  }

  @Test
  void pluralAndCaseAreAccepted() {
    assertEquals(200_200, ok("2 OUROS 2 Pratas").totalBronze());
  }

  @Test
  void excessIsNormalized() {
    assertEquals(Money.of(0, 3, 50), ok("2 prata 150 bronze"));
  }

  @Test
  void copperIsNotACoin() {
    ParseResult.Failure failure = fail("3 cobre");
    assertEquals(ParseError.UNKNOWN_COIN, failure.error());
    assertEquals("cobre", failure.token());
  }

  @ParameterizedTest
  @CsvSource({
    "'',                  EMPTY",
    "1 ouro 2 ouro,       DUPLICATE_COIN",
    "1 ouro 2o,           DUPLICATE_COIN",
    "0 ouro,              NOT_POSITIVE",
    "-1 prata,            NOT_POSITIVE",
    "1.5 ouro,            NOT_INTEGER",
    "'1,5 ouro',          NOT_INTEGER",
    "1.5o,                NOT_INTEGER",
    "ouro,                NOT_A_NUMBER",
    "2,                   MISSING_COIN",
    "1 ouro 5,            MISSING_COIN",
    "2 xyz,               UNKNOWN_COIN",
    "2xyz,                UNKNOWN_COIN",
    "99999999999999999999 bronze, OVERFLOW",
    "92233720368548 ouro, OVERFLOW",
  })
  void errors(String input, ParseError expected) {
    assertEquals(expected, fail(input).error());
  }

  @Test
  void configValuesAcceptZero() {
    assertEquals(Money.ZERO, MoneyParser.parseConfig("0").orElseThrow());
    assertEquals(Money.ZERO, MoneyParser.parseConfig("").orElseThrow());
    assertEquals(Money.of(10, 0, 0), MoneyParser.parseConfig("10 ouro").orElseThrow());
    assertEquals(true, MoneyParser.parseConfig("10 cobre").isEmpty());
  }
}
