package dev.austv.dreamcoins.command;

import dev.austv.dreamcoins.money.Money;
import dev.austv.dreamcoins.money.MoneyParser;
import dev.austv.dreamcoins.money.MoneyParser.ParseError;
import dev.austv.dreamcoins.money.MoneyParser.ParseResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  /**
   * "0", "00", "0o", "0 ouro 0 prata": todo número é zero e, trocando-os por 1, o resto é uma
   * quantia válida (moedas conhecidas, sem repetição). "0 cobre" não é zero — é erro.
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
    return MoneyParser.parse(probe) instanceof ParseResult.Success;
  }

  private static final Pattern ZERO_COMPACT = Pattern.compile("0+(\\p{L}+)");

  /** Chave do {@code messages.yml} para cada erro: {@code amount-errors.unknown-coin}. */
  public static String messageKey(ParseError error) {
    return "amount-errors." + error.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
