package dev.austv.dreamcoins.account;

import dev.austv.dreamcoins.account.AccountLedger.Delta;
import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import dev.austv.dreamcoins.money.Money;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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

  /**
   * Quanto esperar uma operação que já começou no banco antes de declarar o resultado incerto.
   * Precisa cobrir o pior caso de uma operação: conexão do MySQL (3 s) + socket por comando (20 s).
   */
  private static final long RUNNING_TASK_WAIT_MILLIS = TimeUnit.SECONDS.toMillis(30);

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

  /** Ligado no início do {@link #shutdown}: dali em diante nenhuma mutação é aceita. */
  private volatile boolean closed;

  /** Disjuntor do banco para as operações bloqueantes (ver {@link #refuseIfUnavailable}). */
  private final AtomicBoolean stalled = new AtomicBoolean();

  private volatile long probeIntervalMillis = TimeUnit.SECONDS.toMillis(5);

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
    // Grava todo o pendente num lote só, não só o de quem saiu: os dois lados de uma transferência
    // entre online tendem a ir juntos. Não é garantia — conta com flush em voo fica para o próximo
    // lote; operações no cache são write-behind (ver CLAUDE.md).
    submitFlush(ledger.takePending());
    ledger.markOffline(uuid, clock.getAsLong());
  }

  /**
   * Periódico: grava os pendentes (inclusive os que voltaram de um flush falho) de contas sem flush
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
    closed = true;
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

  /** O jogador está online neste servidor (o predicado do Bukkit), não importa o cache. */
  public boolean isOnline(UUID uuid) {
    return isOnline.test(uuid);
  }

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

  /*
   * Depois do shutdown toda mutação devolve UNAVAILABLE. Um plugin que guardou a referência do
   * provedor do Vault continua chamando a instância antiga depois de um disable/reload só deste
   * plugin; sem a trava, um saque em conta do cache daria SUCCESS sem nada chegar ao banco.
   */

  public Outcome deposit(UUID uuid, long amount) {
    return closed ? Outcome.UNAVAILABLE : ledger.deposit(uuid, amount);
  }

  public Outcome withdraw(UUID uuid, long amount) {
    return closed ? Outcome.UNAVAILABLE : ledger.withdraw(uuid, amount);
  }

  public Outcome set(UUID uuid, long amount) {
    return closed ? Outcome.UNAVAILABLE : ledger.set(uuid, amount);
  }

  /**
   * Transferência de uma conta do cache ({@code from}) para uma fora dele ({@code to}), atômica no
   * banco: débito e crédito na mesma transação ({@link Storage#transfer}). Completa na main thread.
   *
   * <p>O débito sai do saldo do cache na hora, como débito em voo ({@link
   * AccountLedger#debitInFlight}) — não pelo pendente, que o flush gravaria de novo. Recusada no
   * banco ou com erro, o valor volta ao saldo do cache; aplicada, o crédito do alvo segue o caminho
   * de qualquer crédito externo (órfão, se ele estiver entrando).
   *
   * <p>Por que não "débito pelo flush + crédito pelo {@code applyOffline}": entre os dois o crédito
   * podia ficar gravado com o débito só no cache — com o pagador tendo um flush em voo, ou com o
   * flush do débito falhando —, e um crash ali criava o valor do pagamento (revisão do PR #4).
   * Numa transação só, o crash leva os dois ou nenhum. E ela é segura mesmo com flush em voo do
   * pagador: a guarda do débito olha o saldo do <i>banco</i>, então se um crédito anterior falhou e
   * o banco tem menos, a transferência inteira é recusada em vez de cortar no piso.
   */
  public CompletableFuture<Outcome> transferToOffline(UUID from, UUID to, long amount) {
    if (closed) {
      return CompletableFuture.completedFuture(Outcome.UNAVAILABLE);
    }
    if (!ledger.contains(from)) {
      return CompletableFuture.completedFuture(Outcome.NO_ACCOUNT);
    }
    if (ledger.contains(to)) {
      return CompletableFuture.completedFuture(ledger.transfer(from, to, amount));
    }
    if (amount > ledger.maxBalance()) {
      return CompletableFuture.completedFuture(Outcome.EXCEEDS_MAX);
    }
    if (ledger.balance(from).orElse(0) < amount) {
      return CompletableFuture.completedFuture(Outcome.INSUFFICIENT);
    }
    // O dinheiro do pagador pode estar só no cache (venda de agora há pouco, ainda pendente): grava
    // o pendente antes, e a fila FIFO garante que o banco já o tenha quando a transferência rodar.
    // Seguro mesmo se esse flush falhar — a transferência é guardada e só é recusada.
    submitFlush(ledger.takePending());
    long max = ledger.maxBalance();
    AtomicBoolean claim = new AtomicBoolean();
    Submitted<Outcome> write =
        db.submit(
            claimed(claim, () -> storage.transfer(from, to, amount, max), Outcome.UNAVAILABLE));
    Outcome debit = ledger.debitInFlight(from, amount, write.seq());
    if (debit != Outcome.OK) {
      // Conferido logo acima, na mesma thread: não deveria acontecer. Se acontecer, a tarefa já
      // está na fila e precisa ser abandonada antes de rodar — o cache não descontou nada.
      if (claim.compareAndSet(false, true)) {
        return CompletableFuture.completedFuture(debit);
      }
      logger.severe(
          "Transferência de " + amount + " bronze de " + from + " rodou sem débito no cache.");
    }
    CompletableFuture<Outcome> out = new CompletableFuture<>();
    write
        .future()
        .whenCompleteAsync(
            (outcome, error) -> {
              boolean applied = error == null && outcome == Outcome.OK;
              long now = clock.getAsLong();
              ledger.debitFinished(from, amount, write.seq(), applied, now);
              if (applied) {
                ledger.creditApplied(to, amount, write.seq(), now);
              }
              if (error != null) {
                out.completeExceptionally(error);
              } else {
                // O cache tinha o suficiente (conferido antes); recusa por saldo no banco é crédito do
                // pagador ainda a caminho (flush em voo ou falho), não falta de dinheiro.
                out.complete(outcome == Outcome.INSUFFICIENT ? Outcome.BUSY : outcome);
              }
            },
            mainThread);
    return out;
  }

  public Outcome transfer(UUID from, UUID to, long amount) {
    return closed ? Outcome.UNAVAILABLE : ledger.transfer(from, to, amount);
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
    return submitOffline(uuid, delta).onMain();
  }

  /**
   * Como {@link #applyOffline}, mas <b>espera o resultado</b> — para a API síncrona do Vault.
   * Chamado na main thread, espera o futuro <i>cru</i> do banco: esperar o que completa na main
   * thread travaria para sempre. A thread do banco nunca espera a main, então não há impasse; o
   * ajuste do ledger (reserva, crédito) chega no tick seguinte, pelo callback de sempre.
   *
   * @throws DatabaseUnavailableException se não rodou — e então <b>nada</b> foi alterado (ver
   *     {@link #await})
   */
  public GuardedResult applyOfflineBlocking(UUID uuid, long delta, long timeoutMillis)
      throws InterruptedException,
          ExecutionException,
          DatabaseUnavailableException,
          OutcomeUnknownException {
    refuseIfUnavailable();
    OfflineWrite write = submitOffline(uuid, delta);
    if (write.claim() == null) {
      return write.raw().get(); // resolvido no cache ou recusado antes de ir ao banco
    }
    return await(write.raw(), write.claim(), timeoutMillis);
  }

  /** Saldo de qualquer conta: do cache, ou lido do banco esperando. Vazio se não existe. */
  public OptionalLong balanceBlocking(UUID uuid, long timeoutMillis)
      throws InterruptedException,
          ExecutionException,
          DatabaseUnavailableException,
          OutcomeUnknownException {
    if (ledger.contains(uuid)) {
      return ledger.balance(uuid);
    }
    refuseIfUnavailable();
    AtomicBoolean claim = new AtomicBoolean();
    return await(
        db.submit(claimed(claim, () -> storage.balance(uuid), OptionalLong.empty())).future(),
        claim,
        timeoutMillis);
  }

  /** Dono de um nome (o último a entrar com ele), lido do banco esperando. */
  public Optional<UUID> findUuidBlocking(String name, long timeoutMillis)
      throws InterruptedException,
          ExecutionException,
          DatabaseUnavailableException,
          OutcomeUnknownException {
    refuseIfUnavailable();
    AtomicBoolean claim = new AtomicBoolean();
    Optional<StoredAccount> found =
        await(
            db.submit(
                    claimed(claim, () -> storage.findByName(name), Optional.<StoredAccount>empty()))
                .future(),
            claim,
            timeoutMillis);
    return found.map(StoredAccount::uuid);
  }

  /**
   * Garante que a conta existe, criando-a com o saldo inicial se faltar. Não conta como login: o
   * {@code last_seen} fica intacto, para não roubar um nome reaproveitado.
   *
   * @return {@code true} se a conta foi criada agora
   */
  public boolean ensureAccountBlocking(UUID uuid, String name, long timeoutMillis)
      throws InterruptedException,
          ExecutionException,
          DatabaseUnavailableException,
          OutcomeUnknownException {
    if (ledger.contains(uuid)) {
      return false;
    }
    refuseIfUnavailable();
    long starting = startingBalance;
    AtomicBoolean claim = new AtomicBoolean();
    return await(
        db.submit(claimed(claim, () -> storage.ensureAccount(uuid, name, starting), false))
            .future(),
        claim,
        timeoutMillis);
  }

  /**
   * Disjuntor: com o banco travado, operação bloqueante nova falha na hora em vez de segurar a
   * main thread. Sem ele, um leilão pagando vinte vendedores offline num tick esperaria vinte
   * timeouts seguidos, e o watchdog do Paper derrubaria o servidor.
   */
  private void refuseIfUnavailable() throws DatabaseUnavailableException {
    if (closed) {
      throw new DatabaseUnavailableException("serviço desligado");
    }
    if (stalled.get()) {
      throw new DatabaseUnavailableException("banco travado (disjuntor aberto)");
    }
  }

  /**
   * A tarefa só roda se conseguir a {@code claim} primeiro. Quem espera e desiste também disputa a
   * claim: se ganhar, a tarefa vira no-op e devolve {@code abandoned}.
   */
  private static <T> SequencedExecutor.Task<T> claimed(
      AtomicBoolean claim, SequencedExecutor.Task<T> task, T abandoned) {
    return () -> claim.compareAndSet(false, true) ? task.call() : abandoned;
  }

  /**
   * Espera a tarefa até {@code timeoutMillis}. Passou disso, abre o disjuntor e tenta
   * <b>abandonar</b> a tarefa: ganhando a claim ela não roda nunca, e a falha é verdadeira — nada
   * mudou. Perdendo, ela já começou, e o resultado real é esperado; se nem assim ele vier, o
   * resultado é <b>desconhecido</b> e isso é dito com outra exceção, porque aí a escrita ainda pode
   * ser gravada.
   *
   * <p>Falha do banco ({@link ExecutionException}) também abre o disjuntor: numa queda a fila
   * costuma estar vazia, a tarefa pega a claim na hora e fica presa no {@code getConnection} — sem
   * abrir aqui, cada chamada seguinte congelaria a main thread pelo timeout de conexão inteiro.
   */
  private <T> T await(CompletableFuture<T> future, AtomicBoolean claim, long timeoutMillis)
      throws InterruptedException,
          ExecutionException,
          DatabaseUnavailableException,
          OutcomeUnknownException {
    try {
      return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (ExecutionException failed) {
      openBreaker();
      throw failed;
    } catch (TimeoutException slow) {
      openBreaker();
      if (claim.compareAndSet(false, true)) {
        throw new DatabaseUnavailableException(
            "banco não respondeu em " + timeoutMillis + " ms; operação abandonada sem efeito");
      }
      try {
        return future.get(RUNNING_TASK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
      } catch (ExecutionException failed) {
        throw failed;
      } catch (TimeoutException stuck) {
        throw new OutcomeUnknownException(
            "operação começou no banco e não terminou em " + RUNNING_TASK_WAIT_MILLIS + " ms");
      }
    }
  }

  /**
   * Abre o disjuntor e agenda a sonda. Ele só fecha quando a sonda <b>acessar o banco com
   * sucesso</b> ({@link Storage#ping}); falhando, outra sonda vem depois de {@code
   * probeIntervalMillis}. Fechar só porque a fila andou fecharia com o banco ainda fora.
   */
  private void openBreaker() {
    if (stalled.compareAndSet(false, true)) {
      logger.warning(
          "Banco indisponível ou lento: operações offline do Vault falham na hora até o banco"
              + " responder de novo.");
      probe();
    }
  }

  private void probe() {
    if (closed) {
      return;
    }
    db.submit(
            () -> {
              storage.ping();
              return null;
            })
        .future()
        .whenComplete(
            (ignored, error) -> {
              if (error == null) {
                stalled.set(false);
                logger.info("Banco respondeu de novo; disjuntor fechado.");
              } else {
                CompletableFuture.runAsync(
                    this::probe,
                    CompletableFuture.delayedExecutor(probeIntervalMillis, TimeUnit.MILLISECONDS));
              }
            });
  }

  /** Intervalo entre sondas com o disjuntor aberto. Configurável para os testes. */
  public void setProbeIntervalMillis(long probeIntervalMillis) {
    this.probeIntervalMillis = probeIntervalMillis;
  }

  /**
   * A escrita offline submetida: o futuro cru do banco, o que completa na main thread e a claim que
   * permite abandoná-la ({@code null} se ela nem foi ao banco).
   */
  private record OfflineWrite(
      CompletableFuture<GuardedResult> raw,
      CompletableFuture<GuardedResult> onMain,
      AtomicBoolean claim) {

    static OfflineWrite done(GuardedResult result) {
      CompletableFuture<GuardedResult> completed = CompletableFuture.completedFuture(result);
      return new OfflineWrite(completed, completed, null);
    }
  }

  private OfflineWrite submitOffline(UUID uuid, long delta) {
    if (closed) {
      return OfflineWrite.done(new GuardedResult(Outcome.UNAVAILABLE, 0));
    }
    // Fora da faixa é recusado antes de virar reserva. Débito pelo teto ABSOLUTO: reservas não
    // estouram o long, e um max-balance reduzido não impede de tirar dinheiro de quem passou dele.
    // Crédito pelo teto configurado.
    if (delta > ledger.maxBalance() || delta < -Money.MAX_TOTAL) {
      Outcome refusal = delta < 0 ? Outcome.INSUFFICIENT : Outcome.EXCEEDS_MAX;
      return OfflineWrite.done(new GuardedResult(refusal, ledger.balance(uuid).orElse(0)));
    }
    if (ledger.contains(uuid)) {
      Outcome outcome = delta >= 0 ? ledger.deposit(uuid, delta) : ledger.withdraw(uuid, -delta);
      return OfflineWrite.done(new GuardedResult(outcome, ledger.balance(uuid).orElse(0)));
    }
    long max = ledger.maxBalance();
    AtomicBoolean claim = new AtomicBoolean();
    Submitted<GuardedResult> write =
        db.submit(
            claimed(
                claim,
                () -> storage.applyGuarded(uuid, delta, max),
                new GuardedResult(Outcome.UNAVAILABLE, 0)));
    if (delta < 0) {
      ledger.holdStarted(uuid, -delta, write.seq());
    }
    CompletableFuture<GuardedResult> out = new CompletableFuture<>();
    write
        .future()
        .whenCompleteAsync(
            (result, error) -> {
              // Abandonada (UNAVAILABLE) conta como não aplicada: a reserva é devolvida.
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
    return new OfflineWrite(write.future(), out, claim);
  }
}
