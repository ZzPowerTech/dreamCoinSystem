package dev.austv.dreamcoins.account;

import dev.austv.dreamcoins.account.AccountLedger.Delta;
import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import dev.austv.dreamcoins.storage.GuardedResult;
import dev.austv.dreamcoins.storage.SequencedExecutor;
import dev.austv.dreamcoins.storage.SequencedExecutor.Submitted;
import dev.austv.dreamcoins.storage.Storage;
import dev.austv.dreamcoins.storage.StoredAccount;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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

  private static final long ORPHAN_MAX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(5);

  private final Storage storage;
  private final SequencedExecutor db;
  private final Executor mainThread;
  private final Predicate<UUID> isOnline;
  private final LongSupplier clock;
  private final Logger logger;
  private final AccountLedger ledger;

  private final Map<UUID, Preloaded> preloaded = new ConcurrentHashMap<>();

  /** Deltas de contas que já saíram do cache e cujo flush falhou. Main thread. */
  private final List<Delta> detached = new ArrayList<>();

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

  /** O jogador entrou: a conta passa para o cache. */
  public void join(UUID uuid, String name) {
    Preloaded loaded = preloaded.remove(uuid);
    if (loaded != null) {
      ledger.loaded(uuid, name, loaded.balance(), loaded.seq(), clock.getAsLong());
      return;
    }
    // Sem pré-login: o plugin subiu com o jogador já online (reload). Carrega em segundo plano;
    // até lá a conta não aceita mutação, que é melhor do que operar sobre um saldo inventado.
    long starting = startingBalance;
    Submitted<Long> load = db.submit(() -> storage.loadOrCreate(uuid, name, starting));
    load.future()
        .whenCompleteAsync(
            (balance, error) -> {
              if (error != null) {
                logger.log(Level.SEVERE, "Falha ao carregar a conta de " + name, error);
              } else if (isOnline.test(uuid)) {
                ledger.loaded(uuid, name, balance, load.seq(), clock.getAsLong());
              }
            },
            mainThread);
  }

  /** O jogador saiu: grava o que falta e tira a conta do cache. */
  public void quit(UUID uuid) {
    ledger.takePending(uuid).ifPresent(delta -> submitFlush(List.of(delta)));
    ledger.remove(uuid);
    preloaded.remove(uuid);
  }

  /** Periódico: grava todos os pendentes. */
  public void flushAll() {
    submitFlush(ledger.takePending());
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
                logger.log(Level.WARNING, "Resync de saldos falhou; tento de novo no próximo ciclo", error);
                return;
              }
              balances.forEach((uuid, balance) -> ledger.resynced(uuid, balance, read.seq()));
            },
            mainThread);
  }

  /** Periódico: descarta sobras de logins que não se concretizaram. */
  public void housekeeping() {
    long now = clock.getAsLong();
    ledger.pruneOrphans(now, ORPHAN_MAX_AGE_MILLIS);
    preloaded.values().removeIf(p -> now - p.atMillis() > ORPHAN_MAX_AGE_MILLIS);
  }

  /**
   * Desligamento: grava tudo e espera. Chamado no {@code onDisable}, quando a main thread já não
   * aceita tarefas — por isso espera o futuro cru, sem callback.
   */
  public void shutdown() {
    List<Delta> batch = new ArrayList<>(ledger.takePending());
    batch.addAll(detached);
    detached.clear();
    if (!batch.isEmpty()) {
      try {
        db.submit(
                () -> {
                  storage.applyDeltas(batch);
                  return null;
                })
            .future()
            .get(15, TimeUnit.SECONDS);
      } catch (Exception error) {
        logger.log(
            Level.SEVERE,
            "Falha ao gravar saldos no desligamento — deltas perdidos: " + batch,
            error);
      }
    }
    db.close();
    storage.close();
  }

  private void submitFlush(List<Delta> deltas) {
    List<Delta> batch = new ArrayList<>(deltas);
    batch.addAll(detached);
    detached.clear();
    if (batch.isEmpty()) {
      return;
    }
    Submitted<Void> write =
        db.submit(
            () -> {
              storage.applyDeltas(batch);
              return null;
            });
    // Seguro registrar depois de submeter: a conclusão volta pela main thread, que é esta.
    ledger.inFlight(batch, write.seq());
    write
        .future()
        .whenCompleteAsync(
            (ignored, error) -> {
              if (error == null) {
                ledger.flushCompleted(write.seq());
                return;
              }
              logger.log(Level.WARNING, "Falha ao gravar saldos; tento de novo no próximo ciclo", error);
              detached.addAll(ledger.flushFailed(write.seq(), batch));
            },
            mainThread);
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
    return onMain(db.submit(() -> storage.findByName(name)).future(), found -> found.map(this::view));
  }

  /**
   * Como {@code thenApplyAsync(fn, mainThread)}, mas a <b>falha</b> também volta pela main thread.
   * O {@code thenApplyAsync} completa o erro na thread que falhou — a do banco —, e o callback de
   * quem chamou rodaria fora da main thread justamente no caminho de erro.
   */
  private <T, R> CompletableFuture<R> onMain(
      CompletableFuture<T> source, java.util.function.Function<T, R> mapper) {
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

  private AccountView view(StoredAccount stored) {
    long balance = ledger.balance(stored.uuid()).orElse(stored.balance());
    return new AccountView(stored.uuid(), stored.name(), balance);
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
   * Altera uma conta que não está no cache, validando no próprio banco. Completa na main thread; se
   * a conta entrou no cache enquanto isso, o ledger decide se precisa somar o delta.
   */
  public CompletableFuture<GuardedResult> applyOffline(UUID uuid, long delta) {
    long max = ledger.maxBalance();
    Submitted<GuardedResult> write = db.submit(() -> storage.applyGuarded(uuid, delta, max));
    return onMain(
        write.future(),
        result -> {
          if (result.outcome() == Outcome.OK) {
            ledger.externalApplied(uuid, delta, write.seq(), clock.getAsLong());
          }
          return result;
        });
  }
}
