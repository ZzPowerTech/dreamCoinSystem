package dev.austv.dreamcoins.money;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Transforma uma quantia em texto: {@code 1 ouro, 2 prata e 3 bronze} ou {@code 1o 2p 3b}.
 *
 * <p>Texto puro, sem cor: a mesma saída serve ao chat (onde o {@code messages.yml} põe a cor em
 * volta), ao {@code format()} do Vault e às placeholders, que não podem carregar tags de
 * MiniMessage para dentro de outro plugin.
 */
public final class MoneyFormatter {

  /** Como uma moeda é escrita: singular, plural e o sufixo da forma curta. */
  public record CoinName(String singular, String plural, String symbol) {}

  private final Map<CoinType, CoinName> names;
  private final String separator;
  private final String lastSeparator;
  private final String thousandsSeparator;

  public MoneyFormatter(
      Map<CoinType, CoinName> names,
      String separator,
      String lastSeparator,
      String thousandsSeparator) {
    EnumMap<CoinType, CoinName> copy = new EnumMap<>(CoinType.class);
    for (CoinType type : CoinType.values()) {
      CoinName name = names.get(type);
      copy.put(type, name != null ? name : defaultName(type));
    }
    this.names = copy;
    this.separator = separator;
    this.lastSeparator = lastSeparator;
    this.thousandsSeparator = thousandsSeparator;
  }

  /** Nomes e separadores padrão do {@code messages.yml}. */
  public static MoneyFormatter defaults() {
    return new MoneyFormatter(Map.of(), ", ", " e ", ".");
  }

  public static CoinName defaultName(CoinType type) {
    return switch (type) {
      case GOLD -> new CoinName("ouro", "ouro", "o");
      case SILVER -> new CoinName("prata", "prata", "p");
      case BRONZE -> new CoinName("bronze", "bronze", "b");
    };
  }

  /** Forma longa, omitindo as moedas zeradas. Saldo zero vira {@code 0 bronze}. */
  public String format(Money money) {
    List<String> parts = new ArrayList<>(3);
    for (CoinType type : CoinType.values()) {
      long amount = money.component(type);
      if (amount > 0) {
        parts.add(amount(type, amount));
      }
    }
    if (parts.isEmpty()) {
      return amount(CoinType.BRONZE, 0);
    }
    if (parts.size() == 1) {
      return parts.getFirst();
    }
    String head = String.join(separator, parts.subList(0, parts.size() - 1));
    return head + lastSeparator + parts.getLast();
  }

  /** Forma curta: {@code 10o 20p 3b}. Saldo zero vira {@code 0b}. */
  public String formatShort(Money money) {
    List<String> parts = new ArrayList<>(3);
    for (CoinType type : CoinType.values()) {
      long amount = money.component(type);
      if (amount > 0) {
        parts.add(number(amount) + names.get(type).symbol());
      }
    }
    return parts.isEmpty() ? "0" + names.get(CoinType.BRONZE).symbol() : String.join(" ", parts);
  }

  /** {@code 3 bronze}, com singular/plural e separador de milhar. */
  public String amount(CoinType type, long amount) {
    return number(amount) + " " + name(type, amount);
  }

  public String name(CoinType type, long amount) {
    CoinName name = names.get(type);
    return amount == 1 ? name.singular() : name.plural();
  }

  /** Número com separador de milhar ({@code 1.002.003}). Não depende do locale da JVM. */
  public String number(long value) {
    String digits = Long.toString(Math.abs(value));
    StringBuilder out = new StringBuilder(digits.length() + digits.length() / 3);
    int firstGroup = digits.length() % 3 == 0 ? 3 : digits.length() % 3;
    out.append(digits, 0, firstGroup);
    for (int i = firstGroup; i < digits.length(); i += 3) {
      out.append(thousandsSeparator).append(digits, i, i + 3);
    }
    return value < 0 ? "-" + out : out.toString();
  }
}
