package dev.austv.dreamcoins.placeholder;

import dev.austv.dreamcoins.money.CoinType;
import dev.austv.dreamcoins.money.Money;
import dev.austv.dreamcoins.money.MoneyFormatter;
import dev.austv.dreamcoins.money.MoneyParser;
import dev.austv.dreamcoins.money.MoneyParser.ParseResult;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * O texto de cada placeholder {@code %dreamcoins_<params>%}, a partir de um saldo. Sem
 * PlaceholderAPI e sem Bukkit — e sem estado: pode rodar em qualquer thread, que é como o TAB e os
 * scoreboards pedem.
 *
 * <ul>
 *   <li>{@code balance} — {@code 10 ouro, 20 prata e 3 bronze}; {@code balance_short} — {@code
 *       10o 20p 3b}
 *   <li>{@code gold}, {@code silver}, {@code bronze} (ou {@code ouro}, {@code prata}) — a parte de
 *       cada moeda na decomposição
 *   <li>{@code total_<moeda>} — o saldo inteiro numa moeda só, arredondado para baixo; com {@code
 *       _formatted}, com separador de milhar
 *   <li>{@code balance_vault} — o valor que outros plugins enxergam pelo Vault ({@code 10020.03})
 *   <li>{@code highest} — a maior moeda que o jogador possui
 *   <li>{@code has_<quantia>} — {@code true}/{@code false}; a quantia com {@code _} no lugar do
 *       espaço: {@code has_1_ouro_50_prata}, {@code has_1o_50p}
 * </ul>
 *
 * <p>Conta não carregada lê como zero, nunca como vazio: uma linha de scoreboard em branco parece
 * defeito. Placeholder desconhecido devolve {@code null}, e o PlaceholderAPI mostra o texto cru —
 * quem escreveu vê o próprio erro.
 */
public final class PlaceholderResolver {

  private final Supplier<MoneyFormatter> formatter;
  private final Supplier<String> noCoin;

  /**
   * @param noCoin o texto de {@code highest} para saldo zero
   */
  public PlaceholderResolver(Supplier<MoneyFormatter> formatter, Supplier<String> noCoin) {
    this.formatter = formatter;
    this.noCoin = noCoin;
  }

  public String resolve(String params, OptionalLong balance) {
    String key = params.toLowerCase(Locale.ROOT);
    Money money = Money.ofBronze(balance.orElse(0));
    MoneyFormatter format = formatter.get();

    switch (key) {
      case "balance" -> {
        return format.format(money);
      }
      case "balance_short" -> {
        return format.formatShort(money);
      }
      case "balance_vault" -> {
        return BigDecimal.valueOf(money.totalBronze()).movePointLeft(2).toPlainString();
      }
      case "highest" -> {
        return money.highest().map(type -> format.name(type, 1)).orElseGet(noCoin);
      }
      default -> {
        // continua abaixo
      }
    }
    if (key.startsWith("has_")) {
      return has(key.substring("has_".length()), money);
    }
    if (key.startsWith("total_")) {
      String rest = key.substring("total_".length());
      boolean formatted = rest.endsWith("_formatted");
      String coinName = formatted ? rest.substring(0, rest.length() - "_formatted".length()) : rest;
      return coin(coinName)
          .map(type -> number(format, money.total(type), formatted))
          .orElse(null);
    }
    boolean formatted = key.endsWith("_formatted");
    String coinName = formatted ? key.substring(0, key.length() - "_formatted".length()) : key;
    return coin(coinName)
        .map(type -> number(format, money.component(type), formatted))
        .orElse(null);
  }

  private static String has(String amount, Money money) {
    if (amount.isEmpty()) {
      return null;
    }
    ParseResult parsed = MoneyParser.parse(Arrays.asList(amount.split("_")));
    if (!(parsed instanceof ParseResult.Success success)) {
      return null;
    }
    return Boolean.toString(money.totalBronze() >= success.money().totalBronze());
  }

  /** {@code gold}/{@code silver}/{@code bronze} ou o nome em português; letra solta não vale. */
  private static Optional<CoinType> coin(String name) {
    return switch (name) {
      case "gold" -> Optional.of(CoinType.GOLD);
      case "silver" -> Optional.of(CoinType.SILVER);
      case "bronze" -> Optional.of(CoinType.BRONZE);
      default -> name.length() > 1 ? CoinType.byAlias(name) : Optional.empty();
    };
  }

  private static String number(MoneyFormatter format, long value, boolean formatted) {
    return formatted ? format.number(value) : Long.toString(value);
  }
}
