package dev.austv.dreamcoins.account;

import dev.austv.dreamcoins.account.AccountLedger.Delta;
import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import dev.austv.dreamcoins.storage.GuardedResult;
import dev.austv.dreamcoins.storage.SequencedExecutor;
import dev.austv.dreamcoins.storage.SequencedExecutor.Submitted;
import dev.austv.dreamcoins.storage.Storage;
import dev.austv.dreamcoins.storage.StoredAccount;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Liga o {@link AccountLedger} (main thread) ao {@link Storage} (thread do banco).
 *
 * <p>Sem import de Bukkit: a main thread entra como um {@link Executor} e o "está online?" como um
 * {@link Predicate}. É o que deixa o ciclo de vida inteiro — pré-login, join, pagamento offline,
 * flush, quit — ser testado contra um SQLite de verdade, com a main thread esvaziada pelo teste.
 *
 * <p>Regra de thread: todo método é chamado na main thread, exceto {@link #preload}, que roda na
 * thread assíncrona do pré-login e só escreve no mapa concorrente {@code preloaded}.
 */
public final class AccountService {

  /** Uma conta para exibição: do cache se o jogador está aqui, senão do banco. */
  public record AccountView(UUID uuid, String name, long balance) {}

  private record Preloaded(String name, long balance, long seq, long atMillis) {}

  /** Um flush submetido e ainda não confirmado na main thread. */
  private record Batch(List<Delta> deltas, CompletableFuture<List<Delta>> future) {}

  private static final long STALE_MAX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(5);

  private final Storage storage;
  private final SequencedExecutor db;
  private final Executor mainThread;
  private final Predicate<UUID> isOnline;
  private final LongSupplier clock;
  private final Logger logger;
  private final AccountLedger ledger;

  private final Map<UUID, Preloaded> preloaded = new ConcurrentHashMap<>();

  /**
   * Flushes em voo, por sequência. Main thread. Existe para o desligamento: ali o callback de falha
   * já não roda (a main thread não aceita tarefas), e sem este registro um flush que falhasse no
   * último instante sumiria sem log.
   */
  private final Map<Long, Batch> batches = new LinkedHashMap<>();

  private volatile long startingBalance;

  public AccountService(
      Storage storage,
      SequencedExecutor db,
      Executor mainThread,
      Predicate<UUID> isOnline,
      LongSupplier clock,
      Logger logger,
      long startingBalance,
      long maxBalance) {
    this.storage = storage;
    this.db = db;
    this.mainThread = mainThread;
    this.isOnline = isOnline;
    this.clock = clock;
    this.logger = logger;
    this.startingBalance = startingBalance;
    this.ledger = new AccountLedger(maxBalance);
  }

  /** Valores do config, relidos no reload. */
  public void configure(long startingBalance, long maxBalance) {
    this.startingBalance = startingBalance;
    ledger.setMaxBalance(maxBalance);
  }

  public long maxBalance() {
    return ledger.maxBalance();
  }

  // ---------------------------------------------------------------- ciclo de vida

  /**
   * Carrega (ou cria) a conta antes de o jogador entrar. <b>Bloqueia</b> — chamado na thread
   * assíncrona do pré-login, que existe exatamente para isso.
   *
   * @throws Exception se o banco falhar; o jogador deve ser recusado, e não entrar com saldo errado
   */
  public void preload(UUID uuid, String name, long timeoutMillis) throws Exception {
    long starting = startingBalance;
    Submitted<Long> load = db.submit(() -> storage.loadOrCreate(uuid, name, starting));
    long balance = load.future().get(timeoutMillis, TimeUnit.MILLISECONDS);
    preloaded.put(uuid, new Preloaded(name, balance, load.seq(), clock.getAsLong()));
  }

  /**
   * O jogador entrou. Se a entrada da sessão anterior ainda está no cache (devendo flush), ela é a
   * verdade. Senão vale a leitura do pré-login — a menos que seja mais antiga que a última escrita
   * da sessão anterior (reconexão rápida), caso em que a conta é relida.
   */
  public void join(UUID uuid, String name) {
    Preloaded loaded = preloaded.remove(uuid);
    if (ledger.reattach(uuid, name)) {
      return;
    }
    if (loaded != null
        && ledger.loaded(uuid, name, loaded.balance(), loaded.seq(), clock.getAsLong())) {
      return;
    }
    // Sem pré-login válido (reload do plugin, ou leitura desatualizada): carrega em segundo plano.
    // Até lá a conta não aceita mutação — melhor do que operar sobre um saldo inventado.
    long starting = startingBalance;
    Submitted<Long> load = db.submit(() -> storage.loadOrCreate(uuid, name, starting));
    load.future()
        .whenCompleteAsync(
            (balance, error) -> {
              if (error != null) {
                logger.log(Level.SEVERE, "Falha ao carregar a conta de " + name, error);
              } else if (isOnline.test(uuid)
                  && !ledger.loaded(uuid, name, balance, load.seq(), clock.getAsLong())) {
                logger.severe("Leitura da conta de " + name + " ainda desatualizada; relogue.");
              }
            },
            mainThread);
  }

  /**
   * O jogador saiu: grava o que falta. A entrada continua no cache até o banco confirmar.
   *
   * <p>Não mexe no {@code preloaded}: num login duplicado o Paper derruba a sessão velha (quit)
   * depois do pré-login da nova, e apagar a leitura dela aqui só forçaria uma releitura — a lápide
   * já decide se aquela leitura serve.
   */
  public void quit(UUID uuid) {
    ledger.takePending(uuid).ifPresent(delta -> submitFlush(List.of(delta)));
    ledger.markOffline(uuid, clock.getAsLong());
  }

  /**
   * Periódico: grava os pendentes (inclusive os que voltaram de um flush falho) das contas sem flush
   * em voo, e despeja as contas offline que ficaram quites sem precisar de flush.
   */
  public void flushAll() {
    submitFlush(ledger.takePending());
    ledger.evictIdleOffline(clock.getAsLong());
  }

  /** Periódico (só MySQL): traz para o cache o que outro servidor gravou. */
  public void resyncOnline() {
    List<UUID> accounts = ledger.accounts();
    if (accounts.isEmpty()) {
      return;
    }
    Submitted<Map<UUID, Long>> read = db.submit(() -> storage.balances(accounts));
    read.future()
        .whenCompleteAsync(
            (balances, error) -> {
              if (error != null) {
                logger.log(Level.WARNING, "Resync de saldos falhou; tento de novo", error);
                return;
              }
              balances.forEach((uuid, balance) -> ledger.resynced(uuid, balance, read.seq()));
            },
            mainThread);
  }

  /** Periódico: descarta sobras de logins que não se concretizaram. */
  public void housekeeping() {
    long now = clock.getAsLong();
    ledger.prune(now, STALE_MAX_AGE_MILLIS);
    preloaded.values().removeIf(p -> now - p.atMillis() > STALE_MAX_AGE_MILLIS);
  }

  /**
   * Desligamento: grava tudo e espera. Chamado no {@code onDisable}, quando a main thread já não
   * aceita tarefas — por isso espera os futuros crus, sem callback.
   */
  public void shutdown() {
    try {
      // Esvazia a fila: depois disto todo flush em voo já tem resultado.
      db.submit(() -> null).future().get(15, TimeUnit.SECONDS);
    } catch (Exception error) {
      logger.log(Level.SEVERE, "O banco não esvaziou a fila no desligamento", error);
    }
    long now = clock.getAsLong();
    List<Delta> lost = new ArrayList<>();
    for (Map.Entry<Long, Batch> entry : batches.entrySet()) {
      Batch batch = entry.getValue();
      if (batch.future().isCompletedExceptionally()) {
        lost.addAll(ledger.flushFailed(entry.getKey(), batch.deltas()));
      } else if (batch.future().isDone()) {
        reportUnapplied(batch.future().getNow(List.of()));
        ledger.flushCompleted(entry.getKey(), now);
      } else {
        logger.severe(
            "Flush ainda sem resposta no desligamento — se falhar, estes deltas se perdem: "
                + batch.deltas());
      }
    }
    batches.clear();
    // A fila já esvaziou e os voos foram resolvidos acima: aqui pode pegar tudo de uma vez.
    List<Delta> last = new ArrayList<>(ledger.takeAllPending());
    last.addAll(lost);
    if (!last.isEmpty()) {
      try {
        reportUnapplied(
            db.submit(() -> storage.applyDeltas(last)).future().get(15, TimeUnit.SECONDS));
      } catch (Exception error) {
        logger.log(
            Level.SEVERE,
            "Falha ao gravar saldos no desligamento — deltas perdidos: " + last,
            error);
      }
    }
    db.close();
    storage.close();
  }

  private void submitFlush(List<Delta> deltas) {
    if (deltas.isEmpty()) {
      return;
    }
    List<Delta> batch = List.copyOf(deltas);
    Submitted<List<Delta>> write = db.submit(() -> storage.applyDeltas(batch));
    // Seguro registrar depois de submeter: a conclusão volta pela main thread, que é esta.
    ledger.inFlight(batch, write.seq());
    batches.put(write.seq(), new Batch(batch, write.future()));
    write
        .future()
        .whenCompleteAsync(
            (unapplied, error) -> {
              if (batches.remove(write.seq()) == null) {
                return; // já tratado pelo desligamento
              }
              if (error != null) {
                logger.log(Level.WARNING, "Falha ao gravar saldos; tento no próximo ciclo", error);
                List<Delta> lost = ledger.flushFailed(write.seq(), batch);
                if (!lost.isEmpty()) {
                  logger.severe("Deltas sem conta no cache após flush falho: " + lost);
                }
                return;
              }
              reportUnapplied(unapplied);
              ledger.flushCompleted(write.seq(), clock.getAsLong());
            },
            mainThread);
  }

  /**
   * O banco não aplicou parte de um delta. Débito: o saldo no banco era menor do que o cache via —
   * em servidor único isso não deveria acontecer, e com MySQL em rede é a janela de troca de
   * servidor. Qualquer outro caso: a conta não existe mais no banco.
   */
  private void reportUnapplied(List<Delta> unapplied) {
    for (Delta delta : unapplied) {
      if (delta.amount() < 0) {
        logger.severe(
            "Saldo de "
                + delta.uuid()
                + " no banco era menor que o do cache; zerado, e "
                + -delta.amount()
                + " bronze gastos não puderam ser debitados.");
      } else {
        logger.severe(
            "Conta "
                + delta.uuid()
                + " não existe mais no banco; "
                + delta.amount()
                + " bronze não puderam ser creditados.");
      }
    }
  }

  // ---------------------------------------------------------------- leitura

  public boolean isLoaded(UUID uuid) {
    return ledger.contains(uuid);
  }

  public OptionalLong balance(UUID uuid) {
    return ledger.balance(uuid);
  }

  /**
   * Busca por nome no banco; se a conta está no cache, o saldo vem dele (o banco pode estar atrás
   * dos pendentes). Completa na main thread.
   */
  public CompletableFuture<Optional<AccountView>> lookup(String name) {
    return onMain(
        db.submit(() -> storage.findByName(name)).future(), found -> found.map(this::view));
  }

  /**
   * Saldo de qualquer conta, completando na main thread: do cache na hora, ou lido do banco. Vazio
   * se a conta não existe.
   */
  public CompletableFuture<OptionalLong> readBalance(UUID uuid) {
    if (ledger.contains(uuid)) {
      return CompletableFuture.completedFuture(ledger.balance(uuid));
    }
    return onMain(db.submit(() -> storage.balance(uuid)).future(), Function.identity());
  }

  private AccountView view(StoredAccount stored) {
    long balance = ledger.balance(stored.uuid()).orElse(stored.balance());
    return new AccountView(stored.uuid(), stored.name(), balance);
  }

  /**
   * Como {@code thenApplyAsync(fn, mainThread)}, mas a <b>falha</b> também volta pela main thread.
   * O {@code thenApplyAsync} completa o erro na thread que falhou — a do banco —, e o callback de
   * quem chamou rodaria fora da main thread justamente no caminho de erro.
   */
  private <T, R> CompletableFuture<R> onMain(CompletableFuture<T> source, Function<T, R> mapper) {
    CompletableFuture<R> out = new CompletableFuture<>();
    source.whenCompleteAsync(
        (value, error) -> {
          if (error != null) {
            out.completeExceptionally(error);
            return;
          }
          try {
            out.complete(mapper.apply(value));
          } catch (RuntimeException mapperError) {
            out.completeExceptionally(mapperError);
          }
        },
        mainThread);
    return out;
  }

  // ---------------------------------------------------------------- mutações no cache

  public Outcome deposit(UUID uuid, long amount) {
    return ledger.deposit(uuid, amount);
  }

  public Outcome withdraw(UUID uuid, long amount) {
    return ledger.withdraw(uuid, amount);
  }

  public Outcome set(UUID uuid, long amount) {
    return ledger.set(uuid, amount);
  }

  public Outcome transfer(UUID from, UUID to, long amount) {
    return ledger.transfer(from, to, amount);
  }

  // ---------------------------------------------------------------- mutações fora do cache

  /**
   * Altera uma conta pelo banco, validando lá. Completa na main thread.
   *
   * <p>Conta no cache vai pelo cache — ele é a verdade dela, e uma escrita direta no banco ficaria
   * invisível até o próximo resync. Retirada fora do cache vira reserva no ledger enquanto está em
   * voo (ver a terceira invariante do {@link AccountLedger}).
   */
  public CompletableFuture<GuardedResult> applyOffline(UUID uuid, long delta) {
    // Fora da faixa é recusado antes de virar reserva: duas reservas de Long.MAX_VALUE estourariam
    // o saldo interno, e -Long.MIN_VALUE nem existe.
    if (delta == Long.MIN_VALUE || Math.abs(delta) > ledger.maxBalance()) {
      Outcome refusal = delta < 0 ? Outcome.INSUFFICIENT : Outcome.EXCEEDS_MAX;
      return CompletableFuture.completedFuture(
          new GuardedResult(refusal, ledger.balance(uuid).orElse(0)));
    }
    if (ledger.contains(uuid)) {
      Outcome outcome = delta >= 0 ? ledger.deposit(uuid, delta) : ledger.withdraw(uuid, -delta);
      return CompletableFuture.completedFuture(
          new GuardedResult(outcome, ledger.balance(uuid).orElse(0)));
    }
    long max = ledger.maxBalance();
    Submitted<GuardedResult> write = db.submit(() -> storage.applyGuarded(uuid, delta, max));
    if (delta < 0) {
      ledger.holdStarted(uuid, -delta, write.seq());
    }
    CompletableFuture<GuardedResult> out = new CompletableFuture<>();
    write
        .future()
        .whenCompleteAsync(
            (result, error) -> {
              boolean applied = error == null && result.outcome() == Outcome.OK;
              long now = clock.getAsLong();
              if (delta < 0) {
                ledger.holdFinished(uuid, -delta, write.seq(), applied, now);
              } else if (applied) {
                ledger.creditApplied(uuid, delta, write.seq(), now);
              }
              if (error != null) {
                out.completeExceptionally(error);
              } else {
                out.complete(result);
              }
            },
            mainThread);
    return out;
  }
}
