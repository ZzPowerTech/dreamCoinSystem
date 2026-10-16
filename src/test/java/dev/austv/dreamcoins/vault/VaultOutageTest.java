package dev.austv.dreamcoins.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.austv.dreamcoins.account.AccountLedger.Delta;
import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import dev.austv.dreamcoins.account.AccountService;
import dev.austv.dreamcoins.money.MoneyFormatter;
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
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Re-revisão do PR #2: uma queda prolongada não pode congelar a main thread a cada chamada. */
class VaultOutageTest {

  /** Simula o banco fora: cada operação leva {@code failMillis} (o timeout de conexão) e falha. */
  private static final class DownableStorage implements Storage {
    volatile long failMillis = 300;
    private final SqliteStorage delegate;
    volatile boolean down;

    DownableStorage(Path file) {
      delegate = new SqliteStorage(file);
    }

    private void maybeFail() throws SQLException {
      if (down) {
        try {
          Thread.sleep(failMillis);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        throw new SQLException("Connection is not available, request timed out");
      }
    }

    @Override
    public void open() throws SQLException {
      delegate.open();
    }

    @Override
    public long loadOrCreate(UUID uuid, String name, long startingBalance) throws SQLException {
      maybeFail();
      return delegate.loadOrCreate(uuid, name, startingBalance);
    }

    @Override
    public boolean ensureAccount(UUID uuid, String name, long startingBalance)
        throws SQLException {
      maybeFail();
      return delegate.ensureAccount(uuid, name, startingBalance);
    }

    @Override
    public List<Delta> applyDeltas(List<Delta> deltas) throws SQLException {
      maybeFail();
      return delegate.applyDeltas(deltas);
    }

    @Override
    public Outcome transfer(UUID from, UUID to, long amount, long maxBalance)
        throws SQLException {
      maybeFail();
      return delegate.transfer(from, to, amount, maxBalance);
    }

    @Override
    public GuardedResult applyGuarded(UUID uuid, long delta, long maxBalance)
        throws SQLException {
      maybeFail();
      return delegate.applyGuarded(uuid, delta, maxBalance);
    }

    @Override
    public OptionalLong balance(UUID uuid) throws SQLException {
      maybeFail();
      return delegate.balance(uuid);
    }

    @Override
    public Optional<StoredAccount> findByName(String name) throws SQLException {
      maybeFail();
      return delegate.findByName(name);
    }

    @Override
    public Map<UUID, Long> balances(Collection<UUID> uuids) throws SQLException {
      maybeFail();
      return delegate.balances(uuids);
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

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
  private DownableStorage storage;
  private SequencedExecutor db;
  private ManualExecutor main;
  private final Set<UUID> online = new HashSet<>();
  private AccountService accounts;
  private VaultBridge vault;
  private final UUID bob = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    storage = new DownableStorage(dir.resolve("coins.db"));
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
            1_000,
            1_000_000);
    accounts.setProbeIntervalMillis(100);
    vault = new VaultBridge(accounts, MoneyFormatter::defaults, 200, Logger.getLogger("test"));
    storage.loadOrCreate(bob, "bob", 1_000); // conta existe, bob offline
  }

  @AfterEach
  void tearDown() {
    storage.down = false;
    db.close();
    storage.close();
  }

  private static long millisOf(Runnable call) {
    long start = System.nanoTime();
    call.run();
    return (System.nanoTime() - start) / 1_000_000;
  }

  @Test
  void sustainedOutageFreezesTheMainThreadOnlyOnce() throws Exception {
    storage.down = true;
    // A primeira paga o timeout de conexão: a tarefa pegou a claim na hora e espera-se o real.
    assertEquals(ResponseType.FAILURE, vault.withdraw(bob, 1.00).type);
    for (int i = 0; i < 5; i++) {
      long elapsed =
          millisOf(() -> assertEquals(ResponseType.FAILURE, vault.withdraw(bob, 1.00).type));
      assertTrue(elapsed < 50, "chamada " + i + " com disjuntor aberto levou " + elapsed + " ms");
    }
    // As sondas falham enquanto o banco está fora: o disjuntor não fecha sozinho.
    Thread.sleep(600);
    long whileDown =
        millisOf(() -> assertEquals(ResponseType.FAILURE, vault.withdraw(bob, 1.00).type));
    assertTrue(whileDown < 50, "sonda fechou o disjuntor com o banco fora: " + whileDown + " ms");

    storage.down = false;
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline
        && vault.withdraw(bob, 1.00).type != ResponseType.SUCCESS) {
      Thread.sleep(50);
    }
    assertEquals(OptionalLong.of(900), storage.balance(bob), "só o saque depois da volta valeu");
  }

  @Test
  void databaseErrorOpensTheBreakerToo() throws Exception {
    storage.down = true;
    assertEquals(ResponseType.FAILURE, vault.deposit(bob, 1.00).type);
    long elapsed = millisOf(() -> assertEquals(0.0, vault.balance(bob)));
    assertTrue(elapsed < 50, "leitura com disjuntor aberto levou " + elapsed + " ms");
  }

  /** Falha mais rápida que o timeout do Vault (conexão 3 s × Vault 5 s): só a falha abre. */
  @Test
  void failureFasterThanTheVaultTimeoutStillOpensTheBreaker() throws Exception {
    storage.failMillis = 150;
    storage.down = true;
    assertEquals(ResponseType.FAILURE, vault.withdraw(bob, 1.00).type);
    long elapsed =
        millisOf(() -> assertEquals(ResponseType.FAILURE, vault.withdraw(bob, 1.00).type));
    assertTrue(elapsed < 50, "sem abrir na falha, cada chamada esperaria a conexão: " + elapsed);
  }
}
