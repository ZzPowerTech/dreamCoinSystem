package dev.austv.dreamcoins.command;

import dev.austv.dreamcoins.money.Money;
import dev.austv.dreamcoins.money.MoneyParser;
import dev.austv.dreamcoins.money.MoneyParser.ParseError;
import dev.austv.dreamcoins.money.MoneyParser.ParseResult;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** A quantia escrita nos argumentos de um comando, a partir de um índice. Sem Bukkit. */
public final class AmountArgs {

  private AmountArgs() {}

  /**
   * @param allowZero {@code true} só para o {@code set}: "0" ou "0 ouro" zera o saldo, que é uma
   *     ordem legítima; para dar, tirar ou pagar, zero é erro de digitação
   */
  public static ParseResult parse(String[] args, int from, boolean allowZero) {
    List<String> tokens =
        from >= args.length ? List.of() : Arrays.asList(args).subList(from, args.length);
    if (allowZero && isZero(tokens)) {
      return new ParseResult.Success(Money.ZERO);
    }
    return MoneyParser.parse(tokens);
  }

  private static boolean isZero(List<String> tokens) {
    if (tokens.size() == 1) {
      return tokens.getFirst().equals("0");
    }
    return tokens.size() == 2
        && tokens.getFirst().equals("0")
        && MoneyParser.parse(List.of("1", tokens.get(1))) instanceof ParseResult.Success;
  }

  /** Chave do {@code messages.yml} para cada erro: {@code amount-errors.unknown-coin}. */
  public static String messageKey(ParseError error) {
    return "amount-errors." + error.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
