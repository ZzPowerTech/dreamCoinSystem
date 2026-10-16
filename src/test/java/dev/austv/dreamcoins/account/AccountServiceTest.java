package dev.austv.dreamcoins.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.austv.dreamcoins.account.AccountLedger.Delta;
import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import dev.austv.dreamcoins.storage.GuardedResult;
import dev.austv.dreamcoins.storage.SequencedExecutor;
import dev.austv.dreamcoins.storage.SqliteStorage;
import dev.austv.dreamcoins.storage.Storage;
import dev.austv.dreamcoins.storage.StoredAccount;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountServiceTest {

  private static final long MAX = 1_000_000;

  /** A "main thread" do teste: só roda o que foi postado quando o teste mandar. */
  private static final class ManualExecutor implements Executor {
    private final Queue<Runnable> queue = new ArrayDeque<>();

    @Override
    public synchronized void execute(Runnable command) {
      queue.add(command);
    }

    void runAll() {
      while (true) {
        Runnable next;
        synchronized (this) {
          next = queue.poll();
        }
        if (next == null) {
          return;
        }
        next.run();
      }
    }
  }

  /** SQLite que pode ser mandado falhar no próximo flush. */
  private static final class FlakyStorage implements Storage {
    private final SqliteStorage delegate;
    volatile boolean failNextFlush;

    FlakyStorage(Path file) {
      this.delegate = new SqliteStorage(file);
    }

    @Override
    public List<Delta> applyDeltas(List<Delta> deltas) throws SQLException {
      if (failNextFlush) {
        failNextFlush = false;
        throw new SQLException("simulated outage");
      }
      return delegate.applyDeltas(deltas);
    }

    @Override
    public void open() throws SQLException {
      delegate.open();
    }

    @Override
    public long loadOrCreate(UUID uuid, String name, long startingBalance) throws SQLException {
      return delegate.loadOrCreate(uuid, name, startingBalance);
    }

    @Override
    public boolean ensureAccount(UUID uuid, String name, long startingBalance)
        throws SQLException {
      return delegate.ensureAccount(uuid, name, startingBalance);
    }

    @Override
    public Outcome transfer(UUID from, UUID to, long amount, long maxBalance)
        throws SQLException {
      return delegate.transfer(from, to, amount, maxBalance);
    }

    @Override
    public GuardedResult applyGuarded(UUID uuid, long delta, long maxBalance)
        throws SQLException {
      return delegate.applyGuarded(uuid, delta, maxBalance);
    }

    @Override
    public OptionalLong balance(UUID uuid) throws SQLException {
      return delegate.balance(uuid);
    }

    @Override
    public Optional<StoredAccount> findByName(String name) throws SQLException {
      return delegate.findByName(name);
    }

    @Override
    public Map<UUID, Long> balances(Collection<UUID> uuids) throws SQLException {
      return delegate.balances(uuids);
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  @TempDir Path dir;
  private FlakyStorage storage;
  private SequencedExecutor db;
  private ManualExecutor main;
  private final Set<UUID> online = new HashSet<>();
  private AccountService service;
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    storage = new FlakyStorage(dir.resolve("coins.db"));
    storage.open();
    db = new SequencedExecutor("test-db");
    main = new ManualExecutor();
    service =
        new AccountService(
            storage,
            db,
            main,
            online::contains,
            System::currentTimeMillis,
            Logger.getLogger("test"),
            100,
            MAX);
  }

  @AfterEach
  void tearDown() {
    db.close();
    storage.close();
  }

  /** Espera a fila do banco esvaziar e roda as conclusões na "main thread". */
  private void settle() throws Exception {
    db.submit(() -> null).future().get();
    main.runAll();
  }

  private void login(UUID uuid, String name) throws Exception {
    service.preload(uuid, name, 5_000);
    online.add(uuid);
    service.join(uuid, name);
  }

  private long stored(UUID uuid) throws Exception {
    return storage.balance(uuid).orElseThrow();
  }

  @Test
  void newPlayerStartsWithTheStartingBalance() throws Exception {
    login(alice, "alice");
    assertEquals(OptionalLong.of(100), service.balance(alice));
    assertEquals(100, stored(alice));
  }

  @Test
  void cachedChangesReachTheDatabaseOnFlush() throws Exception {
    login(alice, "alice");
    service.deposit(alice, 50);
    service.withdraw(alice, 20);
    service.flushAll();
    settle();
    assertEquals(130, stored(alice));
  }

  @Test
  void quitFlushesAndEvicts() throws Exception {
    login(alice, "alice");
    service.deposit(alice, 7);
    online.remove(alice);
    service.quit(alice);
    settle();
    assertFalse(service.isLoaded(alice));
    assertEquals(107, stored(alice));
  }

  @Test
  void transferBetweenOnlinePlayersIsPersistedOnBothSides() throws Exception {
    login(alice, "alice");
    login(bob, "bob");
    assertEquals(Outcome.OK, service.transfer(alice, bob, 60));
    service.flushAll();
    settle();
    assertEquals(40, stored(alice));
    assertEquals(160, stored(bob));
  }

  @Test
  void failedFlushIsRetriedOnTheNextOne() throws Exception {
    login(alice, "alice");
    service.deposit(alice, 5);
    storage.failNextFlush = true;
    service.flushAll();
    settle();
    assertEquals(100, stored(alice));
    service.flushAll();
    settle();
    assertEquals(105, stored(alice));
  }

  @Test
  void failedQuitFlushIsNotLost() throws Exception {
    login(alice, "alice");
    service.deposit(alice, 5);
    storage.failNextFlush = true;
    online.remove(alice);
    service.quit(alice);
    settle();
    assertEquals(100, stored(alice));
    service.flushAll(); // a entrada ficou no cache, devendo: o delta voltou para ela
    settle();
    assertEquals(105, stored(alice));
  }

  @Test
  void offlinePaymentReachesTheDatabase() throws Exception {
    login(bob, "bob");
    online.remove(bob);
    service.quit(bob);
    settle();
    CompletableFuture<GuardedResult> result = service.applyOffline(bob, 40);
    settle();
    assertEquals(new GuardedResult(Outcome.OK, 140), result.get());
  }

  @Test
  void offlinePaymentDuringTheLoginWindowIsNotLost() throws Exception {
    // o pré-login leu 100; o pagamento é gravado DEPOIS da leitura e termina antes do join
    service.preload(bob, "bob", 5_000);
    service.applyOffline(bob, 40);
    settle();
    online.add(bob);
    service.join(bob, "bob");
    assertEquals(OptionalLong.of(140), service.balance(bob));
  }

  @Test
  void offlinePaymentBeforeTheLoginIsNotCountedTwice() throws Exception {
    login(bob, "bob");
    online.remove(bob);
    service.quit(bob);
    settle();
    service.applyOffline(bob, 40);
    settle(); // conclusão vira órfã: a conta não está no cache
    login(bob, "bob"); // pré-login posterior já lê 140
    assertEquals(OptionalLong.of(140), service.balance(bob));
  }

  @Test
  void offlineCreditFinishingAfterTheJoinIsAddedToTheCache() throws Exception {
    service.preload(bob, "bob", 5_000); // lê 100
    service.applyOffline(bob, 40); // gravado depois da leitura
    db.submit(() -> null).future().get();
    online.add(bob);
    service.join(bob, "bob"); // entra com 100; o callback do crédito ainda não rodou
    main.runAll();
    assertEquals(OptionalLong.of(140), service.balance(bob));
    service.flushAll();
    settle();
    assertEquals(140, stored(bob));
  }

  @Test
  void resyncBringsInWhatAnotherServerWrote() throws Exception {
    login(alice, "alice");
    service.deposit(alice, 10); // pendente neste servidor
    storage.applyDeltas(List.of(new Delta(alice, 500))); // "outro servidor"
    service.resyncOnline();
    settle();
    assertEquals(OptionalLong.of(610), service.balance(alice));
    service.flushAll();
    settle();
    assertEquals(610, stored(alice));
  }

  @Test
  void lookupPrefersTheCachedBalance() throws Exception {
    login(alice, "WhiteFox");
    service.deposit(alice, 1);
    CompletableFuture<?> found = service.lookup("whitefox");
    settle();
    assertEquals(
        new AccountService.AccountView(alice, "WhiteFox", 101),
        ((java.util.Optional<?>) found.get()).orElseThrow());
  }

  @Test
  void joinWithoutPreloadLoadsInTheBackground() throws Exception {
    online.add(alice);
    service.join(alice, "alice");
    assertFalse(service.isLoaded(alice));
    settle();
    assertTrue(service.isLoaded(alice));
  }

  @Test
  void shutdownWritesEverythingPending() throws Exception {
    login(alice, "alice");
    service.deposit(alice, 9);
    service.shutdown();
    try (SqliteStorage reopened = new SqliteStorage(dir.resolve("coins.db"))) {
      reopened.open();
      assertEquals(OptionalLong.of(109), reopened.balance(alice));
    }
  }

  @Test
  void flushThatFailsDuringShutdownIsRecoveredByTheFinalWrite() throws Exception {
    login(alice, "alice");
    service.deposit(alice, 9);
    storage.failNextFlush = true;
    service.flushAll(); // o callback de falha nunca roda: a "main thread" não é esvaziada
    service.shutdown();
    try (SqliteStorage reopened = new SqliteStorage(dir.resolve("coins.db"))) {
      reopened.open();
      assertEquals(OptionalLong.of(109), reopened.balance(alice));
    }
  }

  // ---- regressões da revisão do PR #1: nenhum cenário pode criar dinheiro ----

  @Test
  void outageOnQuitThenRelogCannotSpendTwice() throws Exception {
    login(alice, "alice");
    service.withdraw(alice, 60);
    storage.failNextFlush = true;
    online.remove(alice);
    service.quit(alice);
    settle();
    login(alice, "alice");
    assertEquals(OptionalLong.of(40), service.balance(alice));
    assertEquals(Outcome.INSUFFICIENT, service.withdraw(alice, 100));
    service.flushAll();
    settle();
    assertEquals(40, stored(alice));
  }

  @Test
  void outageCallbackAfterTheRejoinStillLandsOnTheRightEntry() throws Exception {
    login(alice, "alice");
    service.withdraw(alice, 60);
    storage.failNextFlush = true;
    online.remove(alice);
    service.quit(alice);
    db.submit(() -> null).future().get(); // flush falhou, mas o callback ainda não rodou
    service.preload(alice, "alice", 5_000);
    online.add(alice);
    service.join(alice, "alice");
    main.runAll();
    assertEquals(OptionalLong.of(40), service.balance(alice));
    service.flushAll();
    settle();
    assertEquals(40, stored(alice));
  }

  @Test
  void fastReconnectWithPreloginBeforeTheQuitFlushIsNotStale() throws Exception {
    login(alice, "alice");
    service.withdraw(alice, 60);
    CountDownLatch gate = new CountDownLatch(1);
    db.submit(
        () -> {
          gate.await();
          return null;
        });
    Thread prelogin =
        new Thread(
            () -> {
              try {
                service.preload(alice, "alice", 5_000);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    prelogin.start();
    Thread.sleep(200); // o pré-login novo entra na fila antes do flush do quit
    online.remove(alice);
    service.quit(alice);
    gate.countDown();
    prelogin.join();
    settle(); // o flush do quit confirma e a conta sai do cache, deixando a lápide
    online.add(alice);
    service.join(alice, "alice"); // leitura do pré-login é anterior ao flush: recusada e relida
    settle();
    assertEquals(OptionalLong.of(40), service.balance(alice));
    assertEquals(40, stored(alice));
  }

  @Test
  void offlineWithdrawalRacingTheJoinCannotBeSpentAgain() throws Exception {
    service.preload(bob, "bob", 5_000); // lê 100
    CompletableFuture<GuardedResult> take = service.applyOffline(bob, -100);
    db.submit(() -> null).future().get(); // gravado no banco, callback ainda pendente
    online.add(bob);
    service.join(bob, "bob");
    assertEquals(OptionalLong.of(0), service.balance(bob));
    assertEquals(Outcome.INSUFFICIENT, service.withdraw(bob, 100));
    main.runAll();
    assertEquals(new GuardedResult(Outcome.OK, 0), take.get());
    assertEquals(OptionalLong.of(0), service.balance(bob));
    service.flushAll();
    settle();
    assertEquals(0, stored(bob));
  }

  @Test
  void refusedOfflineWithdrawalGivesTheReservationBack() throws Exception {
    service.preload(bob, "bob", 5_000); // lê 100
    CompletableFuture<GuardedResult> take = service.applyOffline(bob, -500);
    db.submit(() -> null).future().get();
    online.add(bob);
    service.join(bob, "bob");
    main.runAll();
    assertEquals(Outcome.INSUFFICIENT, take.get().outcome());
    assertEquals(OptionalLong.of(100), service.balance(bob));
  }

  /** Re-revisão do PR #1 (C1): dois flushes da mesma conta em voo criavam dinheiro pelo piso. */
  @Test
  void failedCreditFollowedByADebitFlushCannotCreateMoney() throws Exception {
    login(alice, "alice"); // 100
    service.deposit(alice, 50);
    storage.failNextFlush = true;
    service.flushAll(); // +50 vai falhar
    service.withdraw(alice, 120); // cache 30
    online.remove(alice);
    service.quit(alice); // no mesmo tick, antes do callback de falha
    settle();
    service.flushAll(); // regrava o que falta: +50 - 120
    settle();
    assertEquals(30, stored(alice));
    assertFalse(service.isLoaded(alice), "quitou com o banco: sai do cache");
  }

  /** Re-revisão do PR #1 (M-b): reserva absurda não pode estourar o saldo interno. */
  @Test
  void outOfRangeOfflineChangeIsRefusedBeforeBecomingAReservation() throws Exception {
    service.preload(bob, "bob", 5_000);
    assertEquals(Outcome.INSUFFICIENT, service.applyOffline(bob, -Long.MAX_VALUE).get().outcome());
    assertEquals(Outcome.INSUFFICIENT, service.applyOffline(bob, Long.MIN_VALUE).get().outcome());
    assertEquals(Outcome.EXCEEDS_MAX, service.applyOffline(bob, MAX + 1).get().outcome());
    online.add(bob);
    service.join(bob, "bob");
    assertEquals(OptionalLong.of(100), service.balance(bob));
    assertEquals(Outcome.INSUFFICIENT, service.withdraw(bob, 101));
  }

  @Test
  void offlineChangeToACachedAccountGoesThroughTheCache() throws Exception {
    login(bob, "bob");
    GuardedResult result = service.applyOffline(bob, -30).get();
    assertEquals(new GuardedResult(Outcome.OK, 70), result);
    service.flushAll();
    settle();
    assertEquals(70, stored(bob));
  }
}
