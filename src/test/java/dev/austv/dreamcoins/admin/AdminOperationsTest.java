package dev.austv.dreamcoins.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import dev.austv.dreamcoins.account.AccountService;
import dev.austv.dreamcoins.admin.AdminOperations.Result;
import dev.austv.dreamcoins.storage.SequencedExecutor;
import dev.austv.dreamcoins.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminOperationsTest {

  private static final long MAX = 1_000_000;

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

  @TempDir Path dir;
  private SqliteStorage storage;
  private SequencedExecutor db;
  private ManualExecutor main;
  private final Set<UUID> online = new HashSet<>();
  private AccountService accounts;
  private AdminOperations admin;
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    storage = new SqliteStorage(dir.resolve("coins.db"));
    storage.open();
    db = new SequencedExecutor("test-db");
    main = new ManualExecutor();
    accounts =
        new AccountService(
            storage,
            db,
            main,
            online::contains,
            System::currentTimeMillis,
            Logger.getLogger("test"),
            100,
            MAX);
    admin = new AdminOperations(accounts);
  }

  @AfterEach
  void tearDown() {
    db.close();
    storage.close();
  }

  private void settle() throws Exception {
    for (int i = 0; i < 3; i++) {
      db.submit(() -> null).future().get();
      main.runAll();
    }
  }

  private <T> T await(CompletableFuture<T> future) throws Exception {
    settle();
    assertTrue(future.isDone(), "operação deveria ter terminado");
    return future.get();
  }

  private void login(UUID uuid, String name) throws Exception {
    accounts.preload(uuid, name, 5_000);
    online.add(uuid);
    accounts.join(uuid, name);
  }

  private void logout(UUID uuid) throws Exception {
    online.remove(uuid);
    accounts.quit(uuid);
    settle();
    accounts.flushAll();
    settle();
    assertFalse(accounts.isLoaded(uuid));
  }

  private long stored(UUID uuid) throws Exception {
    return storage.balance(uuid).orElseThrow();
  }

  // ---- online ----

  @Test
  void giveToOnlinePlayer() throws Exception {
    login(alice, "alice");
    assertEquals(new Result(Outcome.OK, 150, false), await(admin.give(alice, 50)));
  }

  @Test
  void takeFromOnlinePlayerRefusesMoreThanTheBalance() throws Exception {
    login(alice, "alice");
    assertEquals(new Result(Outcome.INSUFFICIENT, 100, false), await(admin.take(alice, 101)));
    assertEquals(new Result(Outcome.OK, 0, false), await(admin.take(alice, 100)));
  }

  @Test
  void setOnlinePlayer() throws Exception {
    login(alice, "alice");
    assertEquals(new Result(Outcome.OK, 7, false), await(admin.set(alice, 7)));
    accounts.flushAll();
    settle();
    assertEquals(7, stored(alice));
  }

  @Test
  void setAboveMaxIsRefused() throws Exception {
    login(alice, "alice");
    assertEquals(Outcome.EXCEEDS_MAX, await(admin.set(alice, MAX + 1)).outcome());
  }

  @Test
  void setDuringAPendingOfflineWithdrawalIsBusy() throws Exception {
    accounts.preload(bob, "bob", 5_000);
    accounts.applyOffline(bob, -500); // vai ser recusada; reserva em voo
    db.submit(() -> null).future().get();
    online.add(bob);
    accounts.join(bob, "bob");
    assertEquals(Outcome.BUSY, await(admin.set(bob, 0)).outcome());
  }

  // ---- offline ----

  @Test
  void giveAndTakeOffline() throws Exception {
    login(bob, "bob");
    logout(bob);
    assertEquals(new Result(Outcome.OK, 300, false), await(admin.give(bob, 200)));
    assertEquals(new Result(Outcome.INSUFFICIENT, 300, false), await(admin.take(bob, 301)));
    assertEquals(new Result(Outcome.OK, 0, false), await(admin.take(bob, 300)));
    assertEquals(0, stored(bob));
  }

  @Test
  void setOfflineAppliesTheDifference() throws Exception {
    login(bob, "bob");
    logout(bob);
    assertEquals(new Result(Outcome.OK, 42, false), await(admin.set(bob, 42)));
    assertEquals(42, stored(bob));
    assertEquals(new Result(Outcome.OK, 1000, false), await(admin.set(bob, 1000)));
    assertEquals(new Result(Outcome.OK, 1000, false), await(admin.set(bob, 1000)));
  }

  @Test
  void setOfflineReportsWhenTheBalanceChangedInTheMeantime() throws Exception {
    login(bob, "bob");
    logout(bob);
    CompletableFuture<Result> set = admin.set(bob, 0); // leitura submetida: verá 100
    accounts.applyOffline(bob, 30); // outra escrita entre a leitura e o set
    Result result = await(set);
    assertEquals(Outcome.OK, result.outcome());
    assertTrue(result.drifted(), "o saldo final não é o pedido");
    assertEquals(30, result.balance());
    assertEquals(30, stored(bob));
  }

  /** Revisão do PR #3 (I1): reduzir o teto não pode impedir de tirar dinheiro de quem passou. */
  @Test
  void loweredMaxStillLetsTheAdminReduceAnOfflineBalance() throws Exception {
    login(bob, "bob");
    accounts.deposit(bob, 700); // 800
    logout(bob);
    accounts.configure(100, 500); // teto reduzido a quente, abaixo do saldo dele
    assertEquals(new Result(Outcome.OK, 700, false), await(admin.take(bob, 100)));
    assertEquals(new Result(Outcome.OK, 0, false), await(admin.set(bob, 0)));
    assertEquals(Outcome.EXCEEDS_MAX, await(admin.give(bob, 501)).outcome());
  }

  @Test
  void loweredMaxStillLetsTheAdminReduceAnOnlineBalance() throws Exception {
    login(alice, "alice");
    accounts.deposit(alice, 700);
    accounts.configure(100, 500);
    assertEquals(new Result(Outcome.OK, 700, false), await(admin.take(alice, 100)));
    assertEquals(new Result(Outcome.OK, 300, false), await(admin.set(alice, 300)));
  }

  @Test
  void unknownAccount() throws Exception {
    assertEquals(Outcome.NO_ACCOUNT, await(admin.give(bob, 1)).outcome());
    assertEquals(Outcome.NO_ACCOUNT, await(admin.take(bob, 1)).outcome());
    assertEquals(Outcome.NO_ACCOUNT, await(admin.set(bob, 1)).outcome());
  }

  @Test
  void offlineGiveDuringTheLoginWindowShowsUpInTheCache() throws Exception {
    accounts.preload(bob, "bob", 5_000); // lê 100
    CompletableFuture<Result> give = admin.give(bob, 50);
    db.submit(() -> null).future().get();
    online.add(bob);
    accounts.join(bob, "bob");
    assertEquals(Outcome.OK, await(give).outcome());
    assertEquals(OptionalLong.of(150), accounts.balance(bob));
  }
}
