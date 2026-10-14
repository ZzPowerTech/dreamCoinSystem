package dev.austv.dreamcoins.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.austv.dreamcoins.account.AccountLedger.Delta;
import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountLedgerTest {

  private static final long MAX = 1_000_000;
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();
  private AccountLedger ledger;

  @BeforeEach
  void setUp() {
    ledger = new AccountLedger(MAX);
  }

  private long balance(UUID uuid) {
    return ledger.balance(uuid).orElseThrow();
  }

  @Test
  void unknownAccountHasNoBalanceAndRejectsMutations() {
    assertEquals(OptionalLong.empty(), ledger.balance(alice));
    assertEquals(Outcome.NO_ACCOUNT, ledger.deposit(alice, 10));
    assertEquals(Outcome.NO_ACCOUNT, ledger.withdraw(alice, 10));
  }

  @Test
  void depositAndWithdrawChangeBalanceAndAccumulatePending() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    assertEquals(Outcome.OK, ledger.deposit(alice, 50));
    assertEquals(Outcome.OK, ledger.withdraw(alice, 30));
    assertEquals(120, balance(alice));
    assertEquals(List.of(new Delta(alice, 20)), ledger.takePending());
    assertEquals(List.of(), ledger.takePending());
  }

  @Test
  void withdrawBeyondBalanceIsRefusedAndChangesNothing() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    assertEquals(Outcome.INSUFFICIENT, ledger.withdraw(alice, 101));
    assertEquals(100, balance(alice));
    assertEquals(List.of(), ledger.takePending());
  }

  @Test
  void depositBeyondMaxIsRefused() {
    ledger.loaded(alice, "alice", MAX - 10, 1, 0);
    assertEquals(Outcome.EXCEEDS_MAX, ledger.deposit(alice, 11));
    assertEquals(Outcome.OK, ledger.deposit(alice, 10));
    assertEquals(Outcome.EXCEEDS_MAX, ledger.deposit(alice, Long.MAX_VALUE));
  }

  @Test
  void setIsRecordedAsDelta() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    assertEquals(Outcome.OK, ledger.set(alice, 40));
    assertEquals(40, balance(alice));
    assertEquals(List.of(new Delta(alice, -60)), ledger.takePending());
    assertEquals(Outcome.EXCEEDS_MAX, ledger.set(alice, MAX + 1));
  }

  @Test
  void transferMovesMoneyBetweenTwoCachedAccounts() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.loaded(bob, "bob", 0, 2, 0);
    assertEquals(Outcome.OK, ledger.transfer(alice, bob, 70));
    assertEquals(30, balance(alice));
    assertEquals(70, balance(bob));
  }

  @Test
  void failedTransferChangesNeitherSide() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.loaded(bob, "bob", MAX - 5, 2, 0);
    assertEquals(Outcome.EXCEEDS_MAX, ledger.transfer(alice, bob, 6));
    assertEquals(Outcome.INSUFFICIENT, ledger.transfer(alice, bob, 101));
    assertEquals(100, balance(alice));
    assertEquals(MAX - 5, balance(bob));
    assertEquals(List.of(), ledger.takePending());
    assertThrows(IllegalArgumentException.class, () -> ledger.transfer(alice, alice, 1));
  }

  @Test
  void failedFlushPutsDeltaBackIntoPending() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.deposit(alice, 5);
    List<Delta> batch = ledger.takePending();
    ledger.inFlight(batch, 7);
    ledger.deposit(alice, 1);
    assertEquals(List.of(), ledger.flushFailed(7, batch));
    assertEquals(List.of(new Delta(alice, 6)), ledger.takePending());
  }

  @Test
  void failedFlushOfAccountThatAlreadyLeftIsHandedBack() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.deposit(alice, 5);
    Delta quit = ledger.takePending(alice).orElseThrow();
    ledger.inFlight(List.of(quit), 3);
    ledger.remove(alice);
    assertEquals(List.of(new Delta(alice, 5)), ledger.flushFailed(3, List.of(quit)));
  }

  // ---- reconciliação com escritas que o cache não fez ----

  @Test
  void externalWriteAfterTheLoadIsAddedToTheCache() {
    ledger.loaded(alice, "alice", 100, 5, 0);
    ledger.externalApplied(alice, 40, 6, 0);
    assertEquals(140, balance(alice));
  }

  @Test
  void externalWriteBeforeTheLoadWasAlreadyReadAndIsNotCountedTwice() {
    ledger.loaded(alice, "alice", 140, 6, 0);
    ledger.externalApplied(alice, 40, 5, 0);
    assertEquals(140, balance(alice));
  }

  @Test
  void externalWriteThatFinishesBeforeTheJoinIsReplayedIfTheLoadMissedIt() {
    // pré-login leu o banco no seq 5; o pagamento offline foi gravado no seq 6 e terminou
    // antes de o jogador entrar no cache
    ledger.externalApplied(alice, 40, 6, 0);
    ledger.loaded(alice, "alice", 100, 5, 0);
    assertEquals(140, balance(alice));
  }

  @Test
  void orphanThatTheLoadAlreadySawIsNotReplayed() {
    ledger.externalApplied(alice, 40, 4, 0);
    ledger.loaded(alice, "alice", 140, 5, 0);
    assertEquals(140, balance(alice));
  }

  @Test
  void orphansExpire() {
    ledger.externalApplied(alice, 40, 6, 1_000);
    ledger.pruneOrphans(1_000 + 60_001, 60_000);
    ledger.loaded(alice, "alice", 100, 5, 0);
    assertEquals(100, balance(alice));
  }

  @Test
  void resyncKeepsWritesTheReadCouldNotHaveSeen() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.deposit(alice, 10);
    ledger.inFlight(ledger.takePending(), 3); // flush submetido ANTES da leitura (seq 3 < 4)
    ledger.deposit(alice, 20);
    ledger.inFlight(ledger.takePending(), 5); // flush submetido DEPOIS da leitura (seq 5 > 4)
    ledger.deposit(alice, 1); // ainda pendente
    // o banco no seq 4 tem 100 + 10 + 500 (pagamento feito em outro servidor)
    ledger.resynced(alice, 610, 4);
    assertEquals(610 + 20 + 1, balance(alice));
  }

  @Test
  void staleResyncIsIgnored() {
    ledger.loaded(alice, "alice", 100, 9, 0);
    ledger.resynced(alice, 50, 8);
    assertEquals(100, balance(alice));
  }

  @Test
  void completedFlushIsNoLongerInFlight() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.deposit(alice, 10);
    ledger.inFlight(ledger.takePending(), 3);
    ledger.flushCompleted(3);
    ledger.resynced(alice, 110, 2); // leitura anterior ao flush, mas o flush já saiu da lista
    assertEquals(110, balance(alice));
  }

  @Test
  void removeForgetsTheAccount() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    assertTrue(ledger.contains(alice));
    ledger.remove(alice);
    assertFalse(ledger.contains(alice));
  }
}
