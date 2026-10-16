package dev.austv.dreamcoins.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.austv.dreamcoins.account.AccountLedger.Delta;
import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import dev.austv.dreamcoins.account.AccountService;
import dev.austv.dreamcoins.money.Money;
import dev.austv.dreamcoins.money.MoneyFormatter;
import dev.austv.dreamcoins.money.MoneyParser;
import dev.austv.dreamcoins.money.MoneyParser.ParseResult;
import dev.austv.dreamcoins.payment.PaymentPolicy.Rules;
import dev.austv.dreamcoins.payment.PaymentService.Result;
import dev.austv.dreamcoins.payment.PaymentService.Status;
import dev.austv.dreamcoins.storage.GuardedResult;
import dev.austv.dreamcoins.storage.SequencedExecutor;
import dev.austv.dreamcoins.storage.SqliteStorage;
import dev.austv.dreamcoins.storage.Storage;
import dev.austv.dreamcoins.storage.StoredAccount;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Arrays;
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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentServiceTest {

  private static final long MAX = 100_000_000;
  private static final long TWO_GOLD = Money.of(2, 0, 0).totalBronze();

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

  /** SQLite que pode falhar no próximo flush ou na próxima escrita offline. */
  private static final class FlakyStorage implements Storage {
    private final SqliteStorage delegate;
    volatile boolean failNextFlush;
    volatile boolean failNextGuarded;

    FlakyStorage(Path file) {
      delegate = new SqliteStorage(file);
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
    public boolean ensureAccount(UUID uuid, String name, long startingBalance)
        throws SQLException {
      return delegate.ensureAccount(uuid, name, startingBalance);
    }

    @Override
    public Outcome transfer(UUID from, UUID to, long amount, long maxBalance)
        throws SQLException {
      if (failNextGuarded) {
        failNextGuarded = false;
        throw new SQLException("simulated outage");
      }
      return delegate.transfer(from, to, amount, maxBalance);
    }

    @Override
    public GuardedResult applyGuarded(UUID uuid, long delta, long maxBalance)
        throws SQLException {
      if (failNextGuarded) {
        failNextGuarded = false;
        throw new SQLException("simulated outage");
      }
      return delegate.applyGuarded(uuid, delta, maxBalance);
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
  private final AtomicLong now = new AtomicLong(1_000_000);
  private AccountService accounts;
  private Rules rules = new Rules(1, 0, 0, 0, 30_000, true);
  private PaymentService payments;
  private final UUID payer = UUID.randomUUID();
  private final UUID fox = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    open(dir.resolve("coins.db"), TWO_GOLD);
  }

  private void open(Path file, long starting) throws Exception {
    storage = new FlakyStorage(file);
    storage.open();
    db = new SequencedExecutor("test-db");
    main = new ManualExecutor();
    accounts =
        new AccountService(
            storage, db, main, online::contains, now::get, Logger.getLogger("test"), starting,
            MAX);
    payments = new PaymentService(accounts, () -> rules, now::get, Logger.getLogger("test"));
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

  private Result await(CompletableFuture<Result> future) throws Exception {
    settle();
    assertTrue(future.isDone());
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
  }

  private long stored(UUID uuid) throws Exception {
    accounts.flushAll();
    settle();
    return storage.balance(uuid).orElseThrow();
  }

  private Result pay(UUID target, long amount) throws Exception {
    return await(payments.request(payer, target, "fox", amount, false, false));
  }

  private static long parse(String input) {
    ParseResult result = MoneyParser.parse(Arrays.asList(input.split(" ")));
    return ((ParseResult.Success) result).money().totalBronze();
  }

  /** Os exemplos do pedido, com o pagador começando em 2 ouro e o alvo online e offline. */
  @ParameterizedTest
  @CsvSource({
    "2 prata 1 bronze,        '1 ouro, 997 prata e 99 bronze'",
    "1 ouro 35 bronze,        999 prata e 65 bronze",
    "1 ouro,                  1 ouro",
    "1 ouro 2 prata 3 bronze, 997 prata e 97 bronze",
    "2 bronze,                '1 ouro, 999 prata e 98 bronze'",
  })
  void requestExamples(String amount, String payerAfter) throws Exception {
    for (boolean targetOnline : new boolean[] {true, false}) {
      tearDown();
      online.clear();
      open(dir.resolve("ex-" + targetOnline + ".db"), TWO_GOLD);
      login(payer, "payer");
      login(fox, "fox");
      if (!targetOnline) {
        logout(fox);
      }
      Result result = pay(fox, parse(amount));
      assertEquals(Status.PAID, result.status(), amount);
      assertEquals(
          payerAfter, MoneyFormatter.defaults().format(Money.ofBronze(result.payerBalance())));
      assertEquals(TWO_GOLD - parse(amount), stored(payer));
      assertEquals(TWO_GOLD + parse(amount), stored(fox));
    }
  }

  @Test
  void insufficientFundsChangeNothing() throws Exception {
    login(payer, "payer");
    login(fox, "fox");
    assertEquals(Status.INSUFFICIENT, pay(fox, TWO_GOLD + 1).status());
    logout(fox);
    assertEquals(Status.INSUFFICIENT, pay(fox, TWO_GOLD + 1).status());
    assertEquals(TWO_GOLD, stored(payer));
    assertEquals(TWO_GOLD, stored(fox));
  }

  @Test
  void cannotPaySelf() throws Exception {
    login(payer, "payer");
    assertEquals(Status.SELF, pay(payer, 10).status());
  }

  @Test
  void offlineCreditFailureRefundsThePayer() throws Exception {
    login(payer, "payer");
    login(fox, "fox");
    logout(fox);
    storage.failNextGuarded = true;
    Result result = pay(fox, 500);
    assertEquals(Status.UNAVAILABLE, result.status());
    assertEquals(TWO_GOLD, result.payerBalance());
    assertEquals(TWO_GOLD, stored(payer));
    assertEquals(TWO_GOLD, stored(fox));
  }

  @Test
  void offlineReceiverAtTheCeilingRefundsThePayer() throws Exception {
    login(payer, "payer");
    login(fox, "fox");
    accounts.deposit(fox, MAX - TWO_GOLD);
    logout(fox);
    assertEquals(Status.RECEIVER_FULL, pay(fox, 1).status());
    assertEquals(TWO_GOLD, stored(payer));
    assertEquals(MAX, stored(fox));
  }

  @Test
  void onlineReceiverAtTheCeilingIsRefusedAtomically() throws Exception {
    login(payer, "payer");
    login(fox, "fox");
    accounts.deposit(fox, MAX - TWO_GOLD);
    assertEquals(Status.RECEIVER_FULL, pay(fox, 1).status());
    assertEquals(OptionalLong.of(TWO_GOLD), accounts.balance(payer));
  }

  @Test
  void unknownOfflineTargetRefunds() throws Exception {
    login(payer, "payer");
    UUID ghost = UUID.randomUUID();
    assertEquals(Status.UNKNOWN_TARGET, pay(ghost, 10).status());
    assertEquals(TWO_GOLD, stored(payer));
  }

  @Test
  void offlineTargetsCanBeForbidden() throws Exception {
    rules = new Rules(1, 0, 0, 0, 30_000, false);
    login(payer, "payer");
    login(fox, "fox");
    logout(fox);
    assertEquals(Status.OFFLINE_NOT_ALLOWED, pay(fox, 10).status());
  }

  @Test
  void cooldownStartsWithThePaymentAndFailedPaymentsDoNotCount() throws Exception {
    rules = new Rules(1, 0, 3_000, 0, 30_000, true);
    login(payer, "payer");
    login(fox, "fox");
    assertEquals(Status.INSUFFICIENT, pay(fox, TWO_GOLD + 1).status());
    assertEquals(Status.PAID, pay(fox, 10).status()); // a falha não acionou o cooldown
    Result blocked = pay(fox, 10);
    assertEquals(Status.COOLDOWN, blocked.status());
    assertEquals(3_000, blocked.detail());
    now.addAndGet(3_000);
    assertEquals(Status.PAID, pay(fox, 10).status());
  }

  @Test
  void cooldownBypass() throws Exception {
    rules = new Rules(1, 0, 3_000, 0, 30_000, true);
    login(payer, "payer");
    login(fox, "fox");
    pay(fox, 10);
    assertEquals(
        Status.PAID, await(payments.request(payer, fox, "fox", 10, true, false)).status());
  }

  @Test
  void confirmationFlow() throws Exception {
    rules = new Rules(1, 0, 0, 1_000, 30_000, true);
    login(payer, "payer");
    login(fox, "fox");
    Result asked = pay(fox, 1_001);
    assertEquals(Status.NEEDS_CONFIRMATION, asked.status());
    assertEquals(30_000, asked.detail());
    assertEquals(OptionalLong.of(TWO_GOLD), accounts.balance(payer), "nada debitado antes");
    Result paid = await(payments.confirm(payer, false));
    assertEquals(Status.PAID, paid.status());
    assertEquals(1_001, paid.amount());
    assertEquals(Status.NO_PENDING, await(payments.confirm(payer, false)).status());
  }

  @Test
  void confirmationExpires() throws Exception {
    rules = new Rules(1, 0, 0, 1_000, 30_000, true);
    login(payer, "payer");
    login(fox, "fox");
    pay(fox, 5_000);
    now.addAndGet(30_001);
    assertEquals(Status.NO_PENDING, await(payments.confirm(payer, false)).status());
    assertEquals(OptionalLong.of(TWO_GOLD), accounts.balance(payer));
  }

  @Test
  void newRequestReplacesThePendingOneAndQuitForgetsIt() throws Exception {
    rules = new Rules(1, 0, 0, 1_000, 30_000, true);
    login(payer, "payer");
    login(fox, "fox");
    pay(fox, 5_000);
    pay(fox, 6_000);
    assertEquals(6_000, await(payments.confirm(payer, false)).amount());
    pay(fox, 5_000);
    payments.forget(payer);
    assertEquals(Status.NO_PENDING, await(payments.confirm(payer, false)).status());
  }

  @Test
  void confirmationAboveBypassSkipsTheQuestion() throws Exception {
    rules = new Rules(1, 0, 0, 1_000, 30_000, true);
    login(payer, "payer");
    login(fox, "fox");
    assertEquals(
        Status.PAID, await(payments.request(payer, fox, "fox", 5_000, false, true)).status());
  }

  @Test
  void minimumAndMaximumPerPayment() throws Exception {
    rules = new Rules(100, 1_000, 0, 0, 30_000, true);
    login(payer, "payer");
    login(fox, "fox");
    Result below = pay(fox, 99);
    assertEquals(Status.BELOW_MIN, below.status());
    assertEquals(100, below.detail());
    assertEquals(Status.ABOVE_MAX, pay(fox, 1_001).status());
    assertEquals(Status.PAID, pay(fox, 1_000).status());
  }

  @Test
  void paymentToAPlayerInTheLoginWindowLandsOnce() throws Exception {
    login(payer, "payer");
    login(fox, "fox");
    logout(fox);
    accounts.preload(fox, "fox", 5_000); // pré-login lê 2 ouro
    CompletableFuture<Result> payment = payments.request(payer, fox, "fox", 300, false, false);
    db.submit(() -> null).future().get();
    online.add(fox);
    accounts.join(fox, "fox");
    assertEquals(Status.PAID, await(payment).status());
    assertEquals(OptionalLong.of(TWO_GOLD + 300), accounts.balance(fox));
    assertEquals(TWO_GOLD + 300, stored(fox));
  }

  /**
   * Fuzz de conservação: pagamentos aleatórios entre três jogadores que entram e saem, com quedas
   * de banco no meio. Pagamento move dinheiro, nunca cria nem destrói — a soma tem de ficar igual.
   */
  @Test
  void paymentsNeverCreateOrDestroyMoney() throws Exception {
    for (long seed = 1; seed <= 60; seed++) {
      tearDown();
      online.clear();
      open(dir.resolve("fuzz-" + seed + ".db"), 1_000);
      java.util.Random rnd = new java.util.Random(seed);
      UUID[] players = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
      for (UUID player : players) {
        login(player, player.toString().substring(0, 8));
      }
      List<String> trace = new java.util.ArrayList<>();
      for (int step = 0; step < 40; step++) {
        UUID who = players[rnd.nextInt(3)];
        UUID other = players[rnd.nextInt(3)];
        switch (rnd.nextInt(8)) {
          case 0, 1, 2 -> {
            if (accounts.isLoaded(who) && online.contains(who)) {
              long amount = 1 + rnd.nextInt(700);
              payments.request(who, other, "x", amount, true, true);
              trace.add("pay " + amount);
            }
          }
          case 3 -> {
            if (online.remove(who)) {
              accounts.quit(who);
              payments.forget(who);
              trace.add("quit");
            }
          }
          case 4 -> {
            if (!online.contains(who)) {
              accounts.preload(who, "x", 5_000);
              online.add(who);
              accounts.join(who, "x");
              trace.add("join");
            }
          }
          case 5 -> {
            storage.failNextFlush = rnd.nextBoolean();
            storage.failNextGuarded = rnd.nextBoolean();
            trace.add("fail");
          }
          case 6 -> {
            accounts.flushAll();
            trace.add("flush");
          }
          default -> {
            main.runAll();
            trace.add("main");
          }
        }
      }
      storage.failNextFlush = false;
      storage.failNextGuarded = false;
      for (int i = 0; i < 4; i++) {
        settle();
        accounts.flushAll();
      }
      settle();
      long total = 0;
      for (UUID player : players) {
        long balance = storage.balance(player).orElseThrow();
        assertTrue(balance >= 0, "seed " + seed + " negativo " + trace);
        total += balance;
        OptionalLong cached = accounts.balance(player);
        if (cached.isPresent() && online.contains(player)) {
          assertEquals(balance, cached.getAsLong(), "seed " + seed + " cache " + trace);
        }
      }
      assertEquals(3_000, total, "seed " + seed + " " + trace);
      assertFalse(total != 3_000);
    }
  }

  // ---- revisão do PR #4: um crash nunca pode criar o valor do pagamento ----

  private long dbTotal(UUID... ids) throws Exception {
    long total = 0;
    for (UUID id : ids) {
      total += storage.balance(id).orElseThrow();
    }
    return total;
  }

  /** C2: o pagador tem um flush em voo; o banco nunca pode ter o crédito sem o débito. */
  @Test
  void offlinePaymentWithAFlushInFlightIsAtomicInTheDatabase() throws Exception {
    login(payer, "payer");
    login(fox, "fox");
    logout(fox);
    accounts.deposit(payer, 1);
    accounts.flushAll(); // flush do +1 em voo, callback não rodou
    CompletableFuture<Result> payment = payments.request(payer, fox, "fox", 500, false, false);
    db.submit(() -> null).future().get(); // fila andou; a main thread ainda não
    assertEquals(2 * TWO_GOLD + 1, dbTotal(payer, fox), "crash aqui não pode criar dinheiro");
    assertEquals(Status.PAID, await(payment).status());
    assertEquals(TWO_GOLD + 1 - 500, stored(payer));
    assertEquals(TWO_GOLD + 500, stored(fox));
  }

  /** C3: o flush do pagador falha logo antes do pagamento. */
  @Test
  void offlinePaymentAfterAFailedFlushIsAtomicInTheDatabase() throws Exception {
    login(payer, "payer");
    login(fox, "fox");
    logout(fox);
    accounts.withdraw(payer, 1);
    storage.failNextFlush = true;
    accounts.flushAll(); // vai falhar
    CompletableFuture<Result> payment = payments.request(payer, fox, "fox", 500, false, false);
    db.submit(() -> null).future().get();
    assertEquals(2 * TWO_GOLD, dbTotal(payer, fox), "o -1 falhou; o pagamento, inteiro, entrou");
    assertEquals(Status.PAID, await(payment).status());
    assertEquals(TWO_GOLD - 1 - 500, stored(payer));
    assertEquals(TWO_GOLD + 500, stored(fox));
  }

  @Test
  void twoQuickOfflinePaymentsStayAtomic() throws Exception {
    login(payer, "payer");
    login(fox, "fox");
    logout(fox);
    CompletableFuture<Result> first = payments.request(payer, fox, "fox", 100, true, false);
    CompletableFuture<Result> second = payments.request(payer, fox, "fox", 200, true, false);
    db.submit(() -> null).future().get();
    assertEquals(2 * TWO_GOLD, dbTotal(payer, fox));
    assertEquals(Status.PAID, await(first).status());
    assertEquals(Status.PAID, await(second).status());
    assertEquals(TWO_GOLD - 300, stored(payer));
    assertEquals(TWO_GOLD + 300, stored(fox));
  }

  /** O cache não deixa gastar o que está em voo para um alvo offline. */
  @Test
  void moneyInFlightToAnOfflineTargetCannotBeSpentAgain() throws Exception {
    login(payer, "payer");
    login(fox, "fox");
    logout(fox);
    payments.request(payer, fox, "fox", TWO_GOLD, false, false);
    assertEquals(OptionalLong.of(0), accounts.balance(payer));
    assertEquals(Outcome.INSUFFICIENT, accounts.withdraw(payer, 1));
    settle();
    assertEquals(0, stored(payer));
  }

  /** M2: a regra de "alvo offline" olha o jogador real, não o cache. */
  @Test
  void offlineRuleFollowsTheRealPlayerNotTheCache() throws Exception {
    rules = new Rules(1, 0, 0, 0, 30_000, false);
    login(payer, "payer");
    login(fox, "fox");
    accounts.deposit(fox, 1);
    online.remove(fox);
    accounts.quit(fox); // ainda no cache, devendo flush, mas já saiu
    assertEquals(Status.OFFLINE_NOT_ALLOWED, pay(fox, 10).status());
  }

  /** M1: um pagamento que falha tarde não apaga o cooldown de outro mais novo. */
  @Test
  void lateFailureDoesNotEraseANewerCooldown() throws Exception {
    rules = new Rules(1, 0, 3_000, 0, 30_000, true);
    login(payer, "payer");
    login(fox, "fox");
    logout(fox);
    storage.failNextGuarded = true; // o transfer deste vai falhar
    CompletableFuture<Result> slow = payments.request(payer, fox, "fox", 10, false, false);
    now.addAndGet(5_000);
    login(fox, "fox");
    assertEquals(
        Status.PAID, await(payments.request(payer, fox, "fox", 10, false, false)).status());
    assertEquals(Status.UNAVAILABLE, await(slow).status());
    assertEquals(Status.COOLDOWN, pay(fox, 10).status(), "o cooldown do segundo continua");
  }

  /** M4: confirmação recusada por cooldown não perde o pedido. */
  @Test
  void confirmationRefusedByCooldownKeepsTheRequest() throws Exception {
    rules = new Rules(1, 0, 3_000, 1_000, 30_000, true);
    login(payer, "payer");
    login(fox, "fox");
    assertEquals(Status.NEEDS_CONFIRMATION, pay(fox, 5_000).status());
    assertEquals(Status.PAID, pay(fox, 10).status()); // pequeno, sem confirmar: inicia o cooldown
    assertEquals(Status.COOLDOWN, await(payments.confirm(payer, false)).status());
    now.addAndGet(3_000);
    assertEquals(Status.PAID, await(payments.confirm(payer, false)).status());
  }
}
