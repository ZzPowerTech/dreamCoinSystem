package dev.austv.dreamcoins.account;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * O cache de saldos dos jogadores online e a contabilidade do que ainda não chegou ao banco.
 *
 * <p><b>Só a main thread toca nesta classe.</b> Ela não tem nenhum import de Bukkit nem de JDBC: é
 * a parte que decide, isolada da casca de I/O, para que as regras de concorrência — que erram em
 * silêncio, criando ou sumindo com dinheiro — sejam testáveis sem servidor.
 *
 * <h2>Números de sequência</h2>
 *
 * <p>Toda operação no banco passa por um executor de uma thread só, e cada uma recebe um número de
 * sequência na submissão. Como o executor é FIFO, uma leitura com sequência {@code s} enxergou o
 * efeito de toda escrita com sequência menor que {@code s}, e de nenhuma maior. É com isso que o
 * ledger responde, sem relógio e sem lock, à pergunta "este valor do banco já inclui aquela
 * escrita?":
 *
 * <ul>
 *   <li><b>Escrita externa</b> (pagamento a jogador offline, gravado direto no banco): se terminar
 *       depois de o jogador ter sido carregado por uma leitura <i>anterior</i> a ela, o cache soma o
 *       delta; se a leitura foi posterior, o valor já estava lá e somar seria contar duas vezes.
 *   <li><b>Resync</b> (só MySQL): {@code cache = banco + flushes submetidos depois da leitura +
 *       pendente}. Os flushes anteriores à leitura já estão no número do banco.
 * </ul>
 */
public final class AccountLedger {

  /** Resultado de uma mutação no cache. */
  public enum Outcome {
    OK,
    NO_ACCOUNT,
    INSUFFICIENT,
    EXCEEDS_MAX
  }

  /** Alteração de saldo ainda não gravada. */
  public record Delta(UUID uuid, long amount) {}

  private record InFlight(long seq, long amount) {}

  private record Orphan(long seq, long amount, long recordedAtMillis) {}

  private static final class Entry {
    String name;
    long balance;
    long pending;
    long observedSeq;
    final List<InFlight> inFlight = new ArrayList<>(2);
  }

  private final Map<UUID, Entry> entries = new HashMap<>();

  /**
   * Escritas externas que terminaram com a conta fora do cache. Existem para a janela entre o
   * pré-login (que lê o banco numa thread assíncrona) e a entrada no cache na main thread: um
   * pagamento gravado ali não seria visto por nenhum dos dois lados.
   */
  private final Map<UUID, List<Orphan>> orphans = new HashMap<>();

  private long maxBalance;

  public AccountLedger(long maxBalance) {
    this.maxBalance = maxBalance;
  }

  public void setMaxBalance(long maxBalance) {
    this.maxBalance = maxBalance;
  }

  public long maxBalance() {
    return maxBalance;
  }

  // ---------------------------------------------------------------- carga

  /**
   * A conta foi lida do banco por uma operação de sequência {@code seq}. Reaplica as escritas
   * externas que a leitura não viu.
   */
  public void loaded(UUID uuid, String name, long dbBalance, long seq, long nowMillis) {
    Entry existing = entries.get(uuid);
    if (existing != null) {
      existing.name = name;
      resynced(uuid, dbBalance, seq);
      return;
    }
    Entry entry = new Entry();
    entry.name = name;
    entry.balance = dbBalance;
    entry.observedSeq = seq;
    List<Orphan> missed = orphans.remove(uuid);
    if (missed != null) {
      for (Orphan orphan : missed) {
        if (orphan.seq() > seq) {
          entry.balance += orphan.amount();
        }
      }
    }
    entries.put(uuid, entry);
  }

  public void remove(UUID uuid) {
    entries.remove(uuid);
  }

  public boolean contains(UUID uuid) {
    return entries.containsKey(uuid);
  }

  public OptionalLong balance(UUID uuid) {
    Entry entry = entries.get(uuid);
    return entry == null ? OptionalLong.empty() : OptionalLong.of(entry.balance);
  }

  public Optional<String> name(UUID uuid) {
    Entry entry = entries.get(uuid);
    return entry == null ? Optional.empty() : Optional.of(entry.name);
  }

  // ---------------------------------------------------------------- mutações

  public Outcome deposit(UUID uuid, long amount) {
    requireNonNegative(amount);
    Entry entry = entries.get(uuid);
    if (entry == null) {
      return Outcome.NO_ACCOUNT;
    }
    if (amount > maxBalance - entry.balance) {
      return Outcome.EXCEEDS_MAX;
    }
    apply(entry, amount);
    return Outcome.OK;
  }

  public Outcome withdraw(UUID uuid, long amount) {
    requireNonNegative(amount);
    Entry entry = entries.get(uuid);
    if (entry == null) {
      return Outcome.NO_ACCOUNT;
    }
    if (amount > entry.balance) {
      return Outcome.INSUFFICIENT;
    }
    apply(entry, -amount);
    return Outcome.OK;
  }

  /**
   * Define o saldo, gravado como o delta em relação ao que o cache enxerga. Com MySQL em rede, uma
   * escrita de outro servidor ainda não sincronizada continua somada por cima — escrever o valor
   * absoluto a apagaria.
   */
  public Outcome set(UUID uuid, long target) {
    requireNonNegative(target);
    Entry entry = entries.get(uuid);
    if (entry == null) {
      return Outcome.NO_ACCOUNT;
    }
    if (target > maxBalance) {
      return Outcome.EXCEEDS_MAX;
    }
    apply(entry, target - entry.balance);
    return Outcome.OK;
  }

  /** Débito e crédito juntos: ou os dois acontecem, ou nenhum. */
  public Outcome transfer(UUID from, UUID to, long amount) {
    requireNonNegative(amount);
    if (from.equals(to)) {
      throw new IllegalArgumentException("Transfer to the same account");
    }
    Entry source = entries.get(from);
    Entry target = entries.get(to);
    if (source == null || target == null) {
      return Outcome.NO_ACCOUNT;
    }
    if (amount > source.balance) {
      return Outcome.INSUFFICIENT;
    }
    if (amount > maxBalance - target.balance) {
      return Outcome.EXCEEDS_MAX;
    }
    apply(source, -amount);
    apply(target, amount);
    return Outcome.OK;
  }

  private static void apply(Entry entry, long delta) {
    entry.balance += delta;
    entry.pending += delta;
  }

  private static void requireNonNegative(long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException("Amount cannot be negative: " + amount);
    }
  }

  // ---------------------------------------------------------------- flush

  /** Retira todos os deltas pendentes. Quem chama deve registrá-los com {@link #inFlight}. */
  public List<Delta> takePending() {
    List<Delta> out = new ArrayList<>();
    for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
      if (e.getValue().pending != 0) {
        out.add(new Delta(e.getKey(), e.getValue().pending));
        e.getValue().pending = 0;
      }
    }
    return out;
  }

  /** Retira o pendente de uma conta só (saída do jogador). */
  public Optional<Delta> takePending(UUID uuid) {
    Entry entry = entries.get(uuid);
    if (entry == null || entry.pending == 0) {
      return Optional.empty();
    }
    Delta delta = new Delta(uuid, entry.pending);
    entry.pending = 0;
    return Optional.of(delta);
  }

  /** Os deltas foram submetidos ao banco na operação {@code seq}. */
  public void inFlight(List<Delta> deltas, long seq) {
    for (Delta delta : deltas) {
      Entry entry = entries.get(delta.uuid());
      if (entry != null) {
        entry.inFlight.add(new InFlight(seq, delta.amount()));
      }
    }
  }

  public void flushCompleted(long seq) {
    for (Entry entry : entries.values()) {
      entry.inFlight.removeIf(f -> f.seq() == seq);
    }
  }

  /**
   * A gravação {@code seq} falhou (a transação inteira, nada foi aplicado). Os deltas de contas
   * ainda no cache voltam para o pendente.
   *
   * @param batch o que foi submetido em {@code seq}
   * @return os deltas de contas que já saíram do cache — quem chama precisa retentá-los, senão o
   *     dinheiro some
   */
  public List<Delta> flushFailed(long seq, List<Delta> batch) {
    List<Delta> detached = new ArrayList<>();
    for (Delta delta : batch) {
      Entry entry = entries.get(delta.uuid());
      if (entry == null) {
        detached.add(delta);
        continue;
      }
      entry.inFlight.removeIf(f -> f.seq() == seq);
      entry.pending += delta.amount();
    }
    return detached;
  }

  // ---------------------------------------------------------------- escritas externas

  /**
   * Uma escrita feita direto no banco (sequência {@code seq}) terminou. Se o cache tem a conta e a
   * leitura dela foi anterior à escrita, o delta é somado; senão fica guardado para o caso de a
   * conta estar entrando.
   */
  public void externalApplied(UUID uuid, long delta, long seq, long nowMillis) {
    Entry entry = entries.get(uuid);
    if (entry == null) {
      orphans.computeIfAbsent(uuid, k -> new ArrayList<>(1))
          .add(new Orphan(seq, delta, nowMillis));
      return;
    }
    if (entry.observedSeq < seq) {
      entry.balance += delta;
    }
  }

  /** O banco foi relido na operação {@code seq}. */
  public void resynced(UUID uuid, long dbBalance, long seq) {
    Entry entry = entries.get(uuid);
    if (entry == null || seq < entry.observedSeq) {
      return;
    }
    long notYetInDb = entry.pending;
    for (InFlight flight : entry.inFlight) {
      if (flight.seq() > seq) {
        notYetInDb += flight.amount();
      }
    }
    entry.balance = dbBalance + notYetInDb;
    entry.observedSeq = seq;
  }

  /** Descarta órfãos mais velhos que {@code maxAgeMillis}: a conta não entrou mais. */
  public void pruneOrphans(long nowMillis, long maxAgeMillis) {
    Iterator<Map.Entry<UUID, List<Orphan>>> it = orphans.entrySet().iterator();
    while (it.hasNext()) {
      List<Orphan> list = it.next().getValue();
      list.removeIf(o -> nowMillis - o.recordedAtMillis() > maxAgeMillis);
      if (list.isEmpty()) {
        it.remove();
      }
    }
  }

  /** Contas no cache, para o resync e o flush final. */
  public List<UUID> accounts() {
    return List.copyOf(entries.keySet());
  }
}
