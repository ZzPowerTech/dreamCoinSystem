package dev.austv.dreamcoins.money;

import java.util.Optional;

/**
 * Uma quantia de dinheiro, guardada como um único inteiro em bronze.
 *
 * <p>É isto que faz a conversão entre as moedas ser automática: não existem "três saldos", existe
 * um número só, e ouro/prata/bronze são a decomposição dele na hora de exibir — como reais e
 * centavos. Pagar 35 bronze com 1 ouro dá 0 ouro, 999 prata e 65 bronze sem nenhuma regra de troco.
 *
 * <p>Nunca negativa. Toda aritmética detecta overflow e lança, em vez de dar a volta para um valor
 * negativo ou pequeno.
 */
public record Money(long totalBronze) implements Comparable<Money> {

  public static final Money ZERO = new Money(0);

  public Money {
    if (totalBronze < 0) {
      throw new IllegalArgumentException("Money cannot be negative: " + totalBronze);
    }
  }

  public static Money ofBronze(long bronze) {
    return new Money(bronze);
  }

  /**
   * @throws ArithmeticException se a soma não couber num {@code long}
   * @throws IllegalArgumentException se alguma parte for negativa
   */
  public static Money of(long gold, long silver, long bronze) {
    if (gold < 0 || silver < 0 || bronze < 0) {
      throw new IllegalArgumentException("Coin amounts cannot be negative");
    }
    long total =
        Math.addExact(
            Math.addExact(
                Math.multiplyExact(gold, CoinType.GOLD.bronzeValue()),
                Math.multiplyExact(silver, CoinType.SILVER.bronzeValue())),
            bronze);
    return new Money(total);
  }

  public static Money of(CoinType type, long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException("Coin amounts cannot be negative");
    }
    return new Money(Math.multiplyExact(amount, type.bronzeValue()));
  }

  /** Parte em ouro da decomposição. */
  public long gold() {
    return totalBronze / CoinType.GOLD.bronzeValue();
  }

  /** Parte em prata da decomposição, de 0 a 999. */
  public long silver() {
    return (totalBronze % CoinType.GOLD.bronzeValue()) / CoinType.SILVER.bronzeValue();
  }

  /** Parte em bronze da decomposição, de 0 a 99. */
  public long bronze() {
    return totalBronze % CoinType.SILVER.bronzeValue();
  }

  /** A parte da decomposição que cabe à moeda dada. */
  public long component(CoinType type) {
    return switch (type) {
      case GOLD -> gold();
      case SILVER -> silver();
      case BRONZE -> bronze();
    };
  }

  /** O saldo inteiro expresso numa moeda só, arredondado para baixo. */
  public long total(CoinType type) {
    return totalBronze / type.bronzeValue();
  }

  /** A maior moeda presente na decomposição; vazio se a quantia é zero. */
  public Optional<CoinType> highest() {
    for (CoinType type : CoinType.values()) {
      if (totalBronze >= type.bronzeValue()) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }

  public boolean isZero() {
    return totalBronze == 0;
  }

  /** @throws ArithmeticException em overflow */
  public Money plus(Money other) {
    return new Money(Math.addExact(totalBronze, other.totalBronze));
  }

  /** @throws IllegalArgumentException se o resultado for negativo */
  public Money minus(Money other) {
    return new Money(totalBronze - other.totalBronze);
  }

  @Override
  public int compareTo(Money other) {
    return Long.compare(totalBronze, other.totalBronze);
  }
}
