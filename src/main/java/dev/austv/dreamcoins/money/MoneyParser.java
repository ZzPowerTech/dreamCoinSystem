package dev.austv.dreamcoins.money;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê uma quantia escrita pelo jogador: {@code 1 ouro 2 prata 3 bronze}.
 *
 * <p>Aceita pares {@code <n> <moeda>} e a forma compacta {@code <n><alias>} ({@code 2p 1b}),
 * misturados e em qualquer ordem. O excesso é normalizado pelo próprio {@link Money}: {@code 150
 * bronze} vira 1 prata e 50 bronze.
 *
 * <p>Toda recusa traz o motivo e o token culpado — "moeda desconhecida: cobre" é uma mensagem que o
 * jogador consegue corrigir; "valor inválido" não é.
 */
public final class MoneyParser {

  /** Por que a quantia foi recusada. */
  public enum ParseError {
    EMPTY,
    NOT_A_NUMBER,
    NOT_POSITIVE,
    NOT_INTEGER,
    MISSING_COIN,
    UNKNOWN_COIN,
    DUPLICATE_COIN,
    OVERFLOW
  }

  /** Resultado do parse: a quantia ou o motivo da recusa. */
  public sealed interface ParseResult {
    record Success(Money money) implements ParseResult {}

    /** {@code token} é o trecho que causou o erro; vazio em {@link ParseError#EMPTY}. */
    record Failure(ParseError error, String token) implements ParseResult {}
  }

  private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:[.,]\\d+)?");
  private static final Pattern COMPACT = Pattern.compile("(-?\\d+(?:[.,]\\d+)?)(\\p{L}+)");
  private static final Pattern ZERO_COMPACT = Pattern.compile("0+(\\p{L}+)");

  private MoneyParser() {}

  public static ParseResult parse(List<String> args) {
    if (args.isEmpty()) {
      return new ParseResult.Failure(ParseError.EMPTY, "");
    }
    Set<CoinType> seen = EnumSet.noneOf(CoinType.class);
    Money total = Money.ZERO;
    int i = 0;
    while (i < args.size()) {
      String token = args.get(i);
      String numberText;
      String coinText;
      Matcher compact = COMPACT.matcher(token);
      if (compact.matches()) {
        numberText = compact.group(1);
        coinText = compact.group(2);
        i += 1;
      } else if (NUMBER.matcher(token).matches()) {
        // O número primeiro: "/pagar fox 0" deve dizer "maior que zero", não "faltou a moeda".
        ParseResult.Failure badNumber = validateNumber(token);
        if (badNumber != null) {
          return badNumber;
        }
        if (i + 1 >= args.size()) {
          return new ParseResult.Failure(ParseError.MISSING_COIN, token);
        }
        numberText = token;
        coinText = args.get(i + 1);
        i += 2;
      } else {
        return new ParseResult.Failure(ParseError.NOT_A_NUMBER, token);
      }

      ParseResult.Failure invalid = validateNumber(numberText);
      if (invalid != null) {
        return invalid;
      }
      Optional<CoinType> coin = CoinType.byAlias(coinText);
      if (coin.isEmpty()) {
        return new ParseResult.Failure(ParseError.UNKNOWN_COIN, coinText);
      }
      if (!seen.add(coin.get())) {
        return new ParseResult.Failure(ParseError.DUPLICATE_COIN, coinText);
      }
      try {
        total = total.plus(Money.of(coin.get(), Long.parseLong(numberText)));
      } catch (ArithmeticException | NumberFormatException overflow) {
        return new ParseResult.Failure(ParseError.OVERFLOW, numberText);
      }
    }
    return new ParseResult.Success(total);
  }

  /**
   * Como {@link #parse}, mas zero escrito por inteiro também vale: "0", "00", "0o", "0 ouro 0
   * prata". Para o {@code /moedas set} (zerar o saldo é ordem legítima) e o placeholder {@code
   * has_0}; dar, tirar ou pagar zero continua sendo erro de digitação.
   */
  public static ParseResult parseAllowingZero(List<String> args) {
    return isZero(args) ? new ParseResult.Success(Money.ZERO) : parse(args);
  }

  /**
   * Todo número é zero e, trocando-os por 1, o resto é uma quantia válida (moedas conhecidas, sem
   * repetição). "0 cobre" não é zero — é erro; "0 ouro 5 bronze" também, como no {@code /pagar}.
   */
  private static boolean isZero(List<String> tokens) {
    List<String> probe = new ArrayList<>(tokens.size());
    boolean anyNumber = false;
    for (String token : tokens) {
      Matcher compact = ZERO_COMPACT.matcher(token);
      if (token.matches("0+")) {
        probe.add("1");
        anyNumber = true;
      } else if (compact.matches()) {
        probe.add("1" + compact.group(1));
        anyNumber = true;
      } else if (!token.isEmpty() && Character.isDigit(token.charAt(0))) {
        return false; // um número diferente de zero
      } else {
        probe.add(token);
      }
    }
    if (!anyNumber) {
      return false;
    }
    if (probe.equals(List.of("1"))) {
      return true;
    }
    return parse(probe) instanceof ParseResult.Success;
  }

  /**
   * Quantia vinda do {@code config.yml}. Diferente do comando, aceita zero ({@code "0"} ou vazio),
   * porque "sem teto" e "começa sem dinheiro" são configurações legítimas.
   *
   * @return vazio se o texto não é uma quantia válida
   */
  public static Optional<Money> parseConfig(String text) {
    String trimmed = text == null ? "" : text.trim();
    if (trimmed.isEmpty() || trimmed.equals("0")) {
      return Optional.of(Money.ZERO);
    }
    ParseResult result = parse(Arrays.asList(trimmed.split("\\s+")));
    return result instanceof ParseResult.Success success
        ? Optional.of(success.money())
        : Optional.empty();
  }

  private static ParseResult.Failure validateNumber(String numberText) {
    if (numberText.contains(".") || numberText.contains(",")) {
      return new ParseResult.Failure(ParseError.NOT_INTEGER, numberText);
    }
    if (numberText.startsWith("-")) {
      return new ParseResult.Failure(ParseError.NOT_POSITIVE, numberText);
    }
    // Só dígitos a partir daqui; zeros à esquerda não mudam o valor.
    if (numberText.chars().allMatch(c -> c == '0')) {
      return new ParseResult.Failure(ParseError.NOT_POSITIVE, numberText);
    }
    return null;
  }
}
