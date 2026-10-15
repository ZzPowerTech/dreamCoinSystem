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
 * O cache de saldos e a contabilidade do que ainda não chegou ao banco.
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
 * escrita?".
 *
 * <h2>Três invariantes</h2>
 *
 * <ol>
 *   <li><b>Uma conta só sai do cache quando não deve nada ao banco</b> — sem pendente e sem flush
 *       em voo. Na saída do jogador ela fica marcada como offline até o último flush confirmar; se
 *       ele falhar, o delta volta para a <i>mesma</i> entrada e é regravado no ciclo seguinte. Se o
 *       jogador voltar antes disso, reaproveita a entrada, que é a verdade. Sem esta regra, o delta
 *       da sessão anterior ficaria pendurado fora de qualquer entrada e a sessão nova, lida do
 *       banco, não o enxergaria — e o jogador gastaria duas vezes.
 *   <li><b>Lápide:</b> quando a conta enfim sai, guarda-se a sequência da última escrita dela.
 *       Uma leitura do banco mais antiga que a lápide (o pré-login de quem reconectou rápido) não
 *       inclui aquela escrita e é recusada por {@link #loaded}; quem chama relê.
 *   <li><b>Débito externo é pessimista.</b> Uma retirada gravada direto no banco para uma conta
 *       fora do cache vira uma <i>reserva</i> enquanto está em voo: se a conta for carregada por
 *       uma leitura anterior à retirada, o valor já entra descontado, e é devolvido se a retirada
 *       for recusada. Somar só na conclusão deixaria o jogador gastar o mesmo dinheiro no cache
 *       nesse meio-tempo. Crédito externo pode ser otimista: chegar atrasado nunca permite gastar o
 *       que não existe.
 * </ol>
 */
public final class AccountLedger {

  /** Resultado de uma mutação no cache. */
  public enum Outcome {
    OK,
    NO_ACCOUNT,
    INSUFFICIENT,
    EXCEEDS_MAX,
    /** Há retirada externa aguardando resposta e a operação depende de saber o saldo exato. */
    BUSY,
    /**
     * Nada foi feito: o serviço está desligado, ou o banco travou e a operação foi abandonada antes
     * de rodar. Nunca vem do ledger — é o {@code AccountService} que responde assim.
     */
    UNAVAILABLE
  }

  /** Alteração de saldo ainda não gravada. */
  public record Delta(UUID uuid, long amount) {}

  private record InFlight(long seq, long amount) {}

  private record Orphan(long seq, long amount, long recordedAtMillis) {}

  /** Retirada externa em voo; {@code amount} é positivo. */
  private record Hold(long seq, long amount) {}

  private record Tombstone(long seq, long recordedAtMillis) {}

  private static final class Entry {
    String name;
    long balance;
    long pending;
    long observedSeq;
    long lastWriteSeq;
    boolean online = true;
    final List<InFlight> inFlight = new ArrayList<>(2);
    /** Reservas que esta entrada já descontou do saldo: sequência → quanto foi descontado. */
    final Map<Long, Long> countedHolds = new HashMap<>(1);

    boolean idle() {
      return pending == 0 && inFlight.isEmpty();
    }
  }

  private final Map<UUID, Entry> entries = new HashMap<>();

  /**
   * Escritas externas que terminaram com a conta fora do cache. Cobrem a janela entre o pré-login
   * (que lê o banco numa thread assíncrona) e a entrada no cache na main thread.
   */
  private final Map<UUID, List<Orphan>> orphans = new HashMap<>();

  private final Map<UUID, List<Hold>> holds = new HashMap<>();
  private final Map<UUID, Tombstone> tombstones = new HashMap<>();

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

  // ---------------------------------------------------------------- carga e saída

  /**
   * A conta foi lida do banco por uma operação de sequência {@code seq}.
   *
   * @return {@code false} se a leitura é mais antiga que a última escrita de uma sessão anterior
   *     (lápide) — o valor está desatualizado e quem chama precisa reler
   */
  public boolean loaded(UUID uuid, String name, long dbBalance, long seq, long nowMillis) {
    Entry existing = entries.get(uuid);
    if (existing != null) {
      existing.name = name;
      existing.online = true;
      resynced(uuid, dbBalance, seq);
      return true;
    }
    Tombstone tombstone = tombstones.get(uuid);
    if (tombstone != null && tombstone.seq() > seq) {
      return false;
    }
    tombstones.remove(uuid);

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
    entry.balance = deductHolds(uuid, entry, entry.balance, seq);
    entries.put(uuid, entry);
    return true;
  }

  /**
   * O jogador voltou enquanto a entrada da sessão anterior ainda estava no cache (devendo flush):
   * ela é a verdade, e nenhuma leitura do banco a substitui.
   *
   * @return {@code false} se não havia entrada
   */
  public boolean reattach(UUID uuid, String name) {
    Entry entry = entries.get(uuid);
    if (entry == null) {
      return false;
    }
    entry.name = name;
    entry.online = true;
    return true;
  }

  /** O jogador saiu. A entrada só deixa o cache quando não dever nada ao banco. */
  public void markOffline(UUID uuid, long nowMillis) {
    Entry entry = entries.get(uuid);
    if (entry != null) {
      entry.online = false;
      evictIfIdle(uuid, entry, nowMillis);
    }
  }

  private void evictIfIdle(UUID uuid, Entry entry, long nowMillis) {
    if (entry.online || !entry.idle()) {
      return;
    }
    entries.remove(uuid);
    if (entry.lastWriteSeq > 0) {
      tombstones.put(uuid, new Tombstone(entry.lastWriteSeq, nowMillis));
    }
  }

  public boolean contains(UUID uuid) {
    return entries.containsKey(uuid);
  }

  /**
   * Saldo para exibição e para o Vault, nunca negativo. Por dentro ele pode estar negativo enquanto
   * uma retirada externa aguarda resposta (ver {@link #deductHolds}).
   */
  public OptionalLong balance(UUID uuid) {
    Entry entry = entries.get(uuid);
    return entry == null ? OptionalLong.empty() : OptionalLong.of(Math.max(0, entry.balance));
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
    if (amount > maxBalance - worstCase(entry)) {
      return Outcome.EXCEEDS_MAX;
    }
    apply(entry, amount);
    return Outcome.OK;
  }

  /**
   * O saldo se todas as reservas descontadas forem recusadas — a referência para o teto. Com o
   * saldo cru, uma reserva grande abriria espaço acima do {@code max-balance}.
   */
  private static long worstCase(Entry entry) {
    long held = 0;
    for (long amount : entry.countedHolds.values()) {
      held += amount;
    }
    return entry.balance + held;
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
   *
   * <p>Recusado ({@link Outcome#BUSY}) enquanto houver reserva descontada: o saldo de referência é
   * ambíguo até a resposta, e o delta calculado sobre o saldo descontado viraria dinheiro se a
   * retirada fosse recusada (achado da re-revisão do PR #1).
   */
  public Outcome set(UUID uuid, long target) {
    requireNonNegative(target);
    Entry entry = entries.get(uuid);
    if (entry == null) {
      return Outcome.NO_ACCOUNT;
    }
    if (!entry.countedHolds.isEmpty()) {
      return Outcome.BUSY;
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
    if (amount > maxBalance - worstCase(target)) {
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

  /**
   * Retira os deltas pendentes das contas <b>sem flush em voo</b>. Quem chama deve registrá-los com
   * {@link #inFlight}.
   *
   * <p>No máximo um flush em voo por conta, e não é otimização: com dois, um crédito que falhasse
   * deixaria o débito seguinte bater num banco sem aquele crédito — o piso em zero cortaria o
   * débito, o crédito voltaria e seria regravado, e o jogador ficaria com dinheiro que gastou.
   * Esperando, o crédito falho volta ao pendente <i>antes</i> e os dois seguem somados.
   */
  public List<Delta> takePending() {
    return takePending(false);
  }

  /**
   * Retira <b>todo</b> pendente, mesmo de contas com flush em voo. Só no desligamento, depois de
   * a fila do banco esvaziar e os resultados dos voos terem sido aplicados.
   */
  public List<Delta> takeAllPending() {
    return takePending(true);
  }

  private List<Delta> takePending(boolean includeInFlight) {
    List<Delta> out = new ArrayList<>();
    for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
      Entry entry = e.getValue();
      if (entry.pending != 0 && (includeInFlight || entry.inFlight.isEmpty())) {
        out.add(new Delta(e.getKey(), entry.pending));
        entry.pending = 0;
      }
    }
    return out;
  }

  /**
   * Retira o pendente de uma conta só (saída do jogador). Vazio se ela já tem flush em voo: o
   * pendente sai no ciclo seguinte, e até lá a entrada não deixa o cache.
   */
  public Optional<Delta> takePending(UUID uuid) {
    Entry entry = entries.get(uuid);
    if (entry == null || entry.pending == 0 || !entry.inFlight.isEmpty()) {
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
        entry.lastWriteSeq = Math.max(entry.lastWriteSeq, seq);
      }
    }
  }

  /** A gravação {@code seq} foi confirmada. Contas offline que ficaram quites saem do cache. */
  public void flushCompleted(long seq, long nowMillis) {
    List<UUID> idleOffline = new ArrayList<>();
    for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
      Entry entry = e.getValue();
      if (entry.inFlight.removeIf(f -> f.seq() == seq) && !entry.online && entry.idle()) {
        idleOffline.add(e.getKey());
      }
    }
    for (UUID uuid : idleOffline) {
      evictIfIdle(uuid, entries.get(uuid), nowMillis);
    }
  }

  /**
   * A gravação {@code seq} falhou (a transação inteira, nada foi aplicado). Os deltas voltam ao
   * pendente da entrada, que pela primeira invariante ainda está no cache.
   *
   * @return deltas cuja entrada não existe mais — não deveria acontecer; quem chama registra para
   *     recuperação manual
   */
  public List<Delta> flushFailed(long seq, List<Delta> batch) {
    List<Delta> orphaned = new ArrayList<>();
    for (Delta delta : batch) {
      Entry entry = entries.get(delta.uuid());
      if (entry == null || !entry.inFlight.removeIf(f -> f.seq() == seq)) {
        orphaned.add(delta);
        continue;
      }
      entry.pending += delta.amount();
    }
    return orphaned;
  }

  // ---------------------------------------------------------------- escritas externas

  /**
   * Um <b>crédito</b> gravado direto no banco (sequência {@code seq}) terminou. Se o cache tem a
   * conta e a leitura dela foi anterior ao crédito, ele é somado; sem a conta, fica guardado para o
   * caso de ela estar entrando.
   */
  public void creditApplied(UUID uuid, long amount, long seq, long nowMillis) {
    requireNonNegative(amount);
    Entry entry = entries.get(uuid);
    if (entry == null) {
      orphans.computeIfAbsent(uuid, k -> new ArrayList<>(1))
          .add(new Orphan(seq, amount, nowMillis));
      return;
    }
    entry.lastWriteSeq = Math.max(entry.lastWriteSeq, seq);
    if (entry.observedSeq < seq) {
      entry.balance += amount;
    }
  }

  /** Uma <b>retirada</b> direta no banco foi submetida em {@code seq} para conta fora do cache. */
  public void holdStarted(UUID uuid, long amount, long seq) {
    requireNonNegative(amount);
    holds.computeIfAbsent(uuid, k -> new ArrayList<>(1)).add(new Hold(seq, amount));
  }

  /**
   * A retirada {@code seq} terminou. Recusada ou com erro: se uma entrada já a tinha descontado, o
   * que foi descontado volta. Aplicada sem entrada: vira órfã negativa para uma leitura anterior
   * que ainda vá entrar.
   */
  public void holdFinished(UUID uuid, long amount, long seq, boolean applied, long nowMillis) {
    List<Hold> list = holds.get(uuid);
    if (list != null) {
      list.removeIf(h -> h.seq() == seq);
      if (list.isEmpty()) {
        holds.remove(uuid);
      }
    }
    Entry entry = entries.get(uuid);
    if (entry == null) {
      if (applied) {
        orphans.computeIfAbsent(uuid, k -> new ArrayList<>(1))
            .add(new Orphan(seq, -amount, nowMillis));
      }
      return;
    }
    if (applied) {
      entry.lastWriteSeq = Math.max(entry.lastWriteSeq, seq);
    }
    Long deducted = entry.countedHolds.remove(seq);
    if (deducted != null && !applied) {
      entry.balance += deducted;
    }
  }

  /**
   * Desconta de {@code base} as reservas que uma leitura de sequência {@code seq} não viu.
   *
   * <p>O desconto é <b>cheio e sem piso</b>: o saldo interno pode ficar negativo enquanto uma
   * reserva maior que o valor lido aguarda resposta — por exemplo, porque um crédito anterior a ela
   * ainda não chegou ao cache. Cortar no zero parecia mais seguro e criava dinheiro: se a retirada
   * fosse aplicada, a parte cortada nunca era descontada (achado do fuzz da revisão). Negativo por
   * dentro já recusa toda retirada, e {@link #balance} corta no zero só para exibição.
   */
  private long deductHolds(UUID uuid, Entry entry, long base, long seq) {
    entry.countedHolds.clear();
    long balance = base;
    for (Hold hold : holds.getOrDefault(uuid, List.of())) {
      if (hold.seq() > seq) {
        balance -= hold.amount();
        entry.countedHolds.put(hold.seq(), hold.amount());
      }
    }
    return balance;
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
    entry.balance = deductHolds(uuid, entry, dbBalance + notYetInDb, seq);
    entry.observedSeq = seq;
  }

  /** Descarta órfãos e lápides mais velhos que {@code maxAgeMillis}. */
  public void prune(long nowMillis, long maxAgeMillis) {
    Iterator<Map.Entry<UUID, List<Orphan>>> it = orphans.entrySet().iterator();
    while (it.hasNext()) {
      List<Orphan> list = it.next().getValue();
      list.removeIf(o -> nowMillis - o.recordedAtMillis() > maxAgeMillis);
      if (list.isEmpty()) {
        it.remove();
      }
    }
    tombstones.values().removeIf(t -> nowMillis - t.recordedAtMillis() > maxAgeMillis);
  }

  /**
   * Tira do cache as contas offline que ficaram quites sem passar por um flush — o pendente volta a
   * zero por uma mutação depois do quit, então nenhum {@link #flushCompleted} vai despejá-las.
   */
  public void evictIdleOffline(long nowMillis) {
    List<UUID> idle = new ArrayList<>();
    for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
      if (!e.getValue().online && e.getValue().idle()) {
        idle.add(e.getKey());
      }
    }
    for (UUID uuid : idle) {
      evictIfIdle(uuid, entries.get(uuid), nowMillis);
    }
  }

  /** Contas no cache, para o resync. */
  public List<UUID> accounts() {
    return List.copyOf(entries.keySet());
  }
}
