package dev.austv.dreamcoins.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalLong;

/**
 * Converte entre o {@code double} do Vault e o bronze interno. <b>1.0 no Vault = 1 prata</b>, então
 * o bronze é a segunda casa decimal — o "centavo".
 *
 * <p>Quando outro plugin manda fração abaixo de 1 bronze (uma taxa de 5% sobre 0.03, por exemplo),
 * o arredondamento sempre favorece a conservação do dinheiro: <b>depósito para baixo, saque para
 * cima</b>. Arredondar os dois para o mais próximo deixaria um jogador criar bronze do nada
 * repetindo depósitos de 0.005.
 *
 * <p>A conversão passa por {@link BigDecimal#valueOf(double)}, que usa a representação decimal mais
 * curta do double: {@code 0.29} vira exatamente {@code 0.29}, e não o {@code 28.999…} que
 * {@code 0.29 * 100} dá em ponto flutuante.
 */
public final class VaultAmounts {

  /** Casas decimais anunciadas ao Vault. */
  public static final int FRACTIONAL_DIGITS = 2;

  private VaultAmounts() {}

  /** Para depósito: fração de bronze é descartada. Vazio se negativo, NaN, infinito ou enorme. */
  public static OptionalLong toBronzeFloor(double amount) {
    return convert(amount, RoundingMode.FLOOR);
  }

  /** Para saque e {@code has}: fração de bronze conta como um bronze inteiro. */
  public static OptionalLong toBronzeCeil(double amount) {
    return convert(amount, RoundingMode.CEILING);
  }

  /** Bronze → valor do Vault. Exato enquanto o saldo respeitar o {@code max-balance}. */
  public static double toVault(long bronze) {
    return BigDecimal.valueOf(bronze).movePointLeft(FRACTIONAL_DIGITS).doubleValue();
  }

  private static OptionalLong convert(double amount, RoundingMode mode) {
    if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(
          BigDecimal.valueOf(amount)
              .movePointRight(FRACTIONAL_DIGITS)
              .setScale(0, mode)
              .longValueExact());
    } catch (ArithmeticException tooLarge) {
      return OptionalLong.empty();
    }
  }
}
