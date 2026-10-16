package dev.austv.dreamcoins.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.austv.dreamcoins.account.AccountLedger.Delta;
import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteStorageTest {

  private static final long MAX = 1_000_000;

  @TempDir Path dir;
  private SqliteStorage storage;
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    storage = new SqliteStorage(dir.resolve("coins.db"));
    storage.open();
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  @Test
  void firstLoadCreatesTheAccountWithTheStartingBalance() throws Exception {
    assertEquals(500, storage.loadOrCreate(alice, "alice", 500));
    assertEquals(OptionalLong.of(500), storage.balance(alice));
  }

  @Test
  void laterLoadsKeepTheBalanceAndUpdateTheName() throws Exception {
    storage.loadOrCreate(alice, "alice", 500);
    storage.applyDeltas(List.of(new Delta(alice, 25)));
    assertEquals(525, storage.loadOrCreate(alice, "AliceNova", 500));
    assertEquals("AliceNova", storage.findByName("alicenova").orElseThrow().name());
  }

  @Test
  void ensureAccountCreatesOnceAndNeverTouchesAnExistingOne() throws Exception {
    assertTrue(storage.ensureAccount(alice, "alice", 500));
    assertEquals(OptionalLong.of(500), storage.balance(alice));
    storage.applyDeltas(List.of(new Delta(alice, 10)));
    assertFalse(storage.ensureAccount(alice, "outro", 999));
    assertEquals(OptionalLong.of(510), storage.balance(alice));
    assertEquals("alice", storage.findByName("alice").orElseThrow().name());
    assertTrue(storage.ensureAccount(bob, null, 0), "nome desconhecido não impede a criação");
  }

  @Test
  void schemaSurvivesReopening() throws Exception {
    storage.loadOrCreate(alice, "alice", 7);
    storage.close();
    storage = new SqliteStorage(dir.resolve("coins.db"));
    storage.open();
    assertEquals(OptionalLong.of(7), storage.balance(alice));
  }

  @Test
  void deltasAreAddedNotOverwritten() throws Exception {
    storage.loadOrCreate(alice, "alice", 100);
    storage.loadOrCreate(bob, "bob", 0);
    storage.applyDeltas(List.of(new Delta(alice, -30), new Delta(bob, 30)));
    assertEquals(OptionalLong.of(70), storage.balance(alice));
    assertEquals(OptionalLong.of(30), storage.balance(bob));
  }

  @Test
  void twoServersOnTheSameDatabaseBothCount() throws Exception {
    storage.loadOrCreate(alice, "alice", 100);
    try (SqliteStorage other = new SqliteStorage(dir.resolve("coins.db"))) {
      other.open();
      // cada "servidor" viu 100 e gravou a sua alteração; valor absoluto perderia uma delas
      storage.applyDeltas(List.of(new Delta(alice, 10)));
      other.applyDeltas(List.of(new Delta(alice, 5)));
    }
    assertEquals(OptionalLong.of(115), storage.balance(alice));
  }

  @Test
  void flushNeverDrivesTheBalanceNegativeAndReportsWhatItCouldNotDebit() throws Exception {
    storage.loadOrCreate(alice, "alice", 10);
    storage.loadOrCreate(bob, "bob", 10);
    List<Delta> unapplied =
        storage.applyDeltas(List.of(new Delta(alice, -50), new Delta(bob, -5)));
    assertEquals(List.of(new Delta(alice, -40)), unapplied);
    assertEquals(OptionalLong.of(0), storage.balance(alice));
    assertEquals(OptionalLong.of(5), storage.balance(bob));
  }

  @Test
  void flushToADeletedAccountIsReportedWhole() throws Exception {
    assertEquals(List.of(new Delta(alice, 7)), storage.applyDeltas(List.of(new Delta(alice, 7))));
  }

  @Test
  void reusedNameResolvesToWhoeverLoggedInLast() throws Exception {
    storage.loadOrCreate(alice, "Fox", 1);
    Thread.sleep(5);
    storage.loadOrCreate(bob, "Fox", 2); // alice trocou de nome e não voltou; bob pegou "Fox"
    Thread.sleep(5);
    storage.applyDeltas(List.of(new Delta(alice, 10))); // gravar saldo não é "ser visto"
    assertEquals(bob, storage.findByName("fox").orElseThrow().uuid());
  }

  @Test
  void guardedWriteOutOfRangeNeverReachesTheDatabase() throws Exception {
    storage.loadOrCreate(alice, "alice", 100);
    assertEquals(Outcome.EXCEEDS_MAX, storage.applyGuarded(alice, Long.MAX_VALUE, MAX).outcome());
    assertEquals(
        Outcome.INSUFFICIENT, storage.applyGuarded(alice, Long.MIN_VALUE + 1, MAX).outcome());
    assertEquals(new GuardedResult(Outcome.OK, 100), storage.applyGuarded(alice, 0, MAX));
    assertEquals(Outcome.NO_ACCOUNT, storage.applyGuarded(bob, 0, MAX).outcome());
  }

  @Test
  void guardedWriteSucceedsAndReturnsTheNewBalance() throws Exception {
    storage.loadOrCreate(alice, "alice", 100);
    assertEquals(new GuardedResult(Outcome.OK, 60), storage.applyGuarded(alice, -40, MAX));
    assertEquals(new GuardedResult(Outcome.OK, 160), storage.applyGuarded(alice, 100, MAX));
  }

  @Test
  void guardedDebitIsAllowedAboveTheConfiguredMax() throws Exception {
    storage.loadOrCreate(alice, "alice", 100);
    assertEquals(new GuardedResult(Outcome.OK, 90), storage.applyGuarded(alice, -10, 50));
    assertEquals(Outcome.EXCEEDS_MAX, storage.applyGuarded(alice, 1, 50).outcome());
  }

  @Test
  void transferMovesBothSidesOrNeither() throws Exception {
    storage.loadOrCreate(alice, "alice", 100);
    storage.loadOrCreate(bob, "bob", 0);
    assertEquals(Outcome.OK, storage.transfer(alice, bob, 60, MAX));
    assertEquals(OptionalLong.of(40), storage.balance(alice));
    assertEquals(OptionalLong.of(60), storage.balance(bob));
    assertEquals(Outcome.INSUFFICIENT, storage.transfer(alice, bob, 41, MAX));
    assertEquals(Outcome.EXCEEDS_MAX, storage.transfer(alice, bob, 40, 99));
    assertEquals(Outcome.NO_ACCOUNT, storage.transfer(alice, UUID.randomUUID(), 1, MAX));
    assertEquals(OptionalLong.of(40), storage.balance(alice), "recusa não pode debitar");
    assertEquals(OptionalLong.of(60), storage.balance(bob));
  }

  @Test
  void guardedWriteRefusesAndChangesNothing() throws Exception {
    storage.loadOrCreate(alice, "alice", 100);
    assertEquals(Outcome.INSUFFICIENT, storage.applyGuarded(alice, -101, MAX).outcome());
    assertEquals(Outcome.EXCEEDS_MAX, storage.applyGuarded(alice, MAX, MAX).outcome());
    assertEquals(Outcome.NO_ACCOUNT, storage.applyGuarded(bob, 1, MAX).outcome());
    assertEquals(OptionalLong.of(100), storage.balance(alice));
  }

  @Test
  void findByNameIsCaseInsensitive() throws Exception {
    storage.loadOrCreate(alice, "WhiteFox", 3);
    StoredAccount found = storage.findByName("whitefox").orElseThrow();
    assertEquals(alice, found.uuid());
    assertEquals(3, found.balance());
    assertTrue(storage.findByName("ninguem").isEmpty());
  }

  @Test
  void balancesInBulk() throws Exception {
    storage.loadOrCreate(alice, "alice", 1);
    storage.loadOrCreate(bob, "bob", 2);
    assertEquals(
        Map.of(alice, 1L, bob, 2L), storage.balances(List.of(alice, bob, UUID.randomUUID())));
    assertEquals(Map.of(), storage.balances(List.of()));
  }
}
