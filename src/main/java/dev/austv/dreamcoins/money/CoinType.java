package dev.austv.dreamcoins.money;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * As três moedas, da maior para a menor.
 *
 * <p>As taxas são <b>fixas em código</b>, e não configuráveis, de propósito: o saldo é guardado em
 * bronze, então mudar a taxa depois de ir ao ar reescalaria o saldo de todo mundo em silêncio.
 *
 * <p>Os aliases também são fixos: são o vocabulário dos comandos, e "cobre" ficou de fora por
 * decisão do dono do servidor.
 */
public enum CoinType {
  GOLD(100_000, List.of("ouro", "ouros", "o")),
  SILVER(100, List.of("prata", "pratas", "p")),
  BRONZE(1, List.of("bronze", "bronzes", "b"));

  private static final Map<String, CoinType> BY_ALIAS = new HashMap<>();

  static {
    for (CoinType type : values()) {
      for (String alias : type.aliases) {
        BY_ALIAS.put(alias, type);
      }
    }
  }

  private final long bronzeValue;
  private final List<String> aliases;

  CoinType(long bronzeValue, List<String> aliases) {
    this.bronzeValue = bronzeValue;
    this.aliases = aliases;
  }

  /** Quanto uma unidade desta moeda vale em bronze. */
  public long bronzeValue() {
    return bronzeValue;
  }

  /** Nomes aceitos nos comandos, já em minúsculas; o primeiro é o nome canônico. */
  public List<String> aliases() {
    return aliases;
  }

  public static Optional<CoinType> byAlias(String alias) {
    return Optional.ofNullable(BY_ALIAS.get(alias.toLowerCase(Locale.ROOT)));
  }
}
