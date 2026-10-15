package dev.austv.dreamcoins.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.austv.dreamcoins.account.AccountService;
import dev.austv.dreamcoins.money.MoneyFormatter;
import dev.austv.dreamcoins.storage.SequencedExecutor;
import dev.austv.dreamcoins.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VaultBridgeTest {

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
  private VaultBridge vault;
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
    vault = new VaultBridge(accounts, MoneyFormatter::defaults, 5_000, Logger.getLogger("test"));
  }

  @AfterEach
  void tearDown() {
    db.close();
    storage.close();
  }

  private void settle() throws Exception {
    db.submit(() -> null).future().get();
    main.runAll();
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
    assertFalse(accounts.isLoaded(uuid), "precisa ter saído do cache");
  }

  private long stored(UUID uuid) throws Exception {
    return storage.balance(uuid).orElseThrow();
  }

  // ---- online: tudo pelo cache ----

  @Test
  void depositIsInSilverWithBronzeAsTheCent() throws Exception {
    login(alice, "alice"); // 100 bronze = 1.00
    EconomyResponse response = vault.deposit(alice, 12.50);
    assertEquals(ResponseType.SUCCESS, response.type);
    assertEquals(12.50, response.amount);
    assertEquals(13.50, response.balance);
    assertEquals(OptionalLong.of(1350), accounts.balance(alice));
  }

  @Test
  void balanceIsReportedInVaultUnits() throws Exception {
    login(alice, "alice");
    assertEquals(1.00, vault.balance(alice));
  }

  @Test
  void depositRoundsDownAndWithdrawRoundsUp() throws Exception {
    login(alice, "alice");
    assertEquals(ResponseType.SUCCESS, vault.deposit(alice, 0.009).type);
    assertEquals(OptionalLong.of(100), accounts.balance(alice));
    EconomyResponse withdrawal = vault.withdraw(alice, 0.001);
    assertEquals(ResponseType.SUCCESS, withdrawal.type);
    assertEquals(0.01, withdrawal.amount);
    assertEquals(OptionalLong.of(99), accounts.balance(alice));
  }

  @Test
  void hasRoundsUpLikeAWithdrawal() throws Exception {
    login(alice, "alice"); // 1.00
    assertTrue(vault.has(alice, 1.00));
    assertFalse(vault.has(alice, 1.001));
    assertTrue(vault.has(alice, 0));
    assertTrue(vault.has(alice, -5));
  }

  @Test
  void withdrawBeyondBalanceFailsAndChangesNothing() throws Exception {
    login(alice, "alice");
    EconomyResponse response = vault.withdraw(alice, 1.01);
    assertEquals(ResponseType.FAILURE, response.type);
    assertEquals(1.00, response.balance);
    assertEquals(OptionalLong.of(100), accounts.balance(alice));
  }

  @Test
  void invalidAmountsFail() throws Exception {
    login(alice, "alice");
    assertEquals(ResponseType.FAILURE, vault.deposit(alice, -1).type);
    assertEquals(ResponseType.FAILURE, vault.withdraw(alice, -1).type);
    assertEquals(ResponseType.FAILURE, vault.deposit(alice, Double.NaN).type);
    assertEquals(ResponseType.FAILURE, vault.deposit(alice, Double.POSITIVE_INFINITY).type);
    assertEquals(ResponseType.FAILURE, vault.deposit(alice, 1e300).type);
    assertEquals(OptionalLong.of(100), accounts.balance(alice));
  }

  @Test
  void depositBeyondMaxBalanceFails() throws Exception {
    login(alice, "alice");
    assertEquals(ResponseType.FAILURE, vault.deposit(alice, MAX / 100.0).type);
    assertEquals(ResponseType.SUCCESS, vault.deposit(alice, (MAX - 100) / 100.0).type);
  }

  // ---- offline: banco, esperando ----

  @Test
  void offlineBalanceIsReadFromTheDatabase() throws Exception {
    login(bob, "bob");
    accounts.deposit(bob, 150);
    logout(bob);
    assertEquals(2.50, vault.balance(bob));
    assertTrue(vault.has(bob, 2.50));
    assertFalse(vault.has(bob, 2.51));
  }

  @Test
  void offlineDepositAndWithdrawGoStraightToTheDatabase() throws Exception {
    login(bob, "bob");
    logout(bob);
    EconomyResponse deposit = vault.deposit(bob, 5.25);
    assertEquals(ResponseType.SUCCESS, deposit.type);
    assertEquals(6.25, deposit.balance);
    assertEquals(625, stored(bob));
    EconomyResponse withdrawal = vault.withdraw(bob, 6.00);
    assertEquals(ResponseType.SUCCESS, withdrawal.type);
    assertEquals(0.25, withdrawal.balance);
    assertEquals(25, stored(bob));
    settle(); // callbacks do ledger (reserva) rodam depois, sem efeito colateral
    assertEquals(25, stored(bob));
  }

  @Test
  void offlineWithdrawBeyondBalanceFails() throws Exception {
    login(bob, "bob");
    logout(bob);
    assertEquals(ResponseType.FAILURE, vault.withdraw(bob, 1.01).type);
    assertEquals(100, stored(bob));
  }

  @Test
  void offlineWithdrawalDuringTheLoginWindowIsAlreadyDeducted() throws Exception {
    accounts.preload(bob, "bob", 5_000); // lê 100
    assertEquals(ResponseType.SUCCESS, vault.withdraw(bob, 1.00).type);
    online.add(bob);
    accounts.join(bob, "bob"); // callback da reserva ainda não rodou
    assertEquals(OptionalLong.of(0), accounts.balance(bob));
    settle();
    assertEquals(OptionalLong.of(0), accounts.balance(bob));
    assertEquals(0, stored(bob));
  }

  @Test
  void unknownAccount() throws Exception {
    assertFalse(vault.hasAccount(bob));
    assertEquals(0, vault.balance(bob));
    assertFalse(vault.has(bob, 0.01));
    assertEquals(ResponseType.FAILURE, vault.deposit(bob, 1).type);
    assertEquals(ResponseType.FAILURE, vault.withdraw(bob, 1).type);
  }

  @Test
  void createAccountUsesTheStartingBalanceAndIsIdempotent() throws Exception {
    assertTrue(vault.createAccount(bob, "bob"));
    assertTrue(vault.hasAccount(bob));
    assertEquals(1.00, vault.balance(bob));
    assertFalse(vault.createAccount(bob, "bob"));
    login(bob, "bob");
    assertFalse(vault.createAccount(bob, "bob"));
  }

  @Test
  void createdAccountDoesNotStealAReusedName() throws Exception {
    login(alice, "Fox");
    logout(alice);
    vault.createAccount(bob, "Fox"); // criado por plugin, nunca entrou
    assertEquals(alice, accounts.findUuidBlocking("fox", 5_000).orElseThrow());
  }

  // ---- revisão do PR #2 ----

  /** I1: referência antiga ao provedor, guardada por uma loja, depois de um disable só deste. */
  @Test
  void staleProviderAfterShutdownRefusesEverything() throws Exception {
    login(alice, "alice");
    accounts.shutdown();
    assertEquals(ResponseType.FAILURE, vault.withdraw(alice, 0.30).type);
    assertEquals(ResponseType.FAILURE, vault.deposit(alice, 0.30).type);
    assertEquals(ResponseType.FAILURE, vault.withdraw(bob, 0.30).type);
    try (SqliteStorage reopened = new SqliteStorage(dir.resolve("coins.db"))) {
      reopened.open();
      assertEquals(OptionalLong.of(100), reopened.balance(alice));
    }
  }

  /** I3: banco travado — a falha é verdadeira e o disjuntor poupa a main thread. */
  @Test
  void stuckDatabaseFailsTruthfullyAndTheBreakerSparesTheMainThread() throws Exception {
    login(bob, "bob");
    logout(bob); // 100 no banco
    VaultBridge impatient =
        new VaultBridge(accounts, MoneyFormatter::defaults, 200, Logger.getLogger("test"));
    CountDownLatch gate = new CountDownLatch(1);
    db.submit(
        () -> {
          gate.await();
          return null;
        });

    assertEquals(ResponseType.FAILURE, impatient.withdraw(bob, 0.50).type); // esperou 200 ms
    long start = System.nanoTime();
    assertEquals(ResponseType.FAILURE, impatient.withdraw(bob, 0.50).type); // disjuntor: na hora
    assertEquals(ResponseType.FAILURE, impatient.deposit(bob, 0.50).type);
    assertTrue(System.nanoTime() - start < 100_000_000L, "disjuntor aberto não pode esperar");

    gate.countDown();
    settle();
    assertEquals(100, stored(bob), "a operação abandonada não pode ter rodado");
    assertEquals(ResponseType.SUCCESS, impatient.withdraw(bob, 0.50).type); // disjuntor fechou
    assertEquals(50, stored(bob));
  }

  @Test
  void abandonedWithdrawalReleasesTheReservationOfAPlayerWhoJoined() throws Exception {
    VaultBridge impatient =
        new VaultBridge(accounts, MoneyFormatter::defaults, 200, Logger.getLogger("test"));
    accounts.preload(bob, "bob", 5_000); // lê 100
    CountDownLatch gate = new CountDownLatch(1);
    db.submit(
        () -> {
          gate.await();
          return null;
        });
    assertEquals(ResponseType.FAILURE, impatient.withdraw(bob, 1.00).type);
    online.add(bob);
    accounts.join(bob, "bob"); // a reserva ainda está contada
    gate.countDown();
    settle();
    assertEquals(OptionalLong.of(100), accounts.balance(bob));
    assertEquals(100, stored(bob));
  }

  // ---- formatação ----

  @Test
  void formatUsesTheThreeCoins() {
    assertEquals("12 prata e 50 bronze", vault.format(12.50));
    assertEquals("1 ouro, 2 prata e 3 bronze", vault.format(1002.03));
    assertEquals("0 bronze", vault.format(0));
    assertEquals("-1 prata", vault.format(-1));
    assertEquals("0 bronze", vault.format(Double.NaN));
  }

  @Test
  void currencyNamesAreSilver() {
    assertEquals("prata", vault.currencyNameSingular());
    assertEquals("prata", vault.currencyNamePlural());
  }
}
