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
  void pendingWaitsWhileTheAccountHasAFlushInFlight() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.deposit(alice, 50);
    ledger.inFlight(ledger.takePending(), 3);
    ledger.withdraw(alice, 120);
    assertEquals(List.of(), ledger.takePending());
    assertTrue(ledger.takePending(alice).isEmpty());
    ledger.flushFailed(3, List.of(new Delta(alice, 50)));
    // o crédito falho volta ANTES de o débito sair: os dois seguem juntos
    assertEquals(List.of(new Delta(alice, -70)), ledger.takePending());
  }

  // ---- espelho para leitura fora da main thread (PR 5) ----

  /**
   * Passeio aleatório por todas as operações que mexem em saldo: o espelho tem de bater com a
   * leitura da main thread depois de cada passo. Um caminho que escrevesse o saldo sem passar pelo
   * {@code setBalance} deixaria o espelho para trás, e é isto que pega.
   */
  @Test
  void mirrorAlwaysMatchesTheBalance() {
    java.util.Random rnd = new java.util.Random(7);
    UUID[] ids = {alice, bob, UUID.randomUUID()};
    long seq = 1;
    for (int step = 0; step < 5_000; step++) {
      UUID who = ids[rnd.nextInt(ids.length)];
      UUID other = ids[rnd.nextInt(ids.length)];
      long amount = rnd.nextInt(500);
      seq++;
      switch (rnd.nextInt(12)) {
        case 0 -> ledger.loaded(who, "x", rnd.nextInt(2_000), seq, 0);
        case 1 -> ledger.deposit(who, amount);
        case 2 -> ledger.withdraw(who, amount);
        case 3 -> ledger.set(who, amount);
        case 4 -> {
          if (!who.equals(other)) {
            ledger.transfer(who, other, amount);
          }
        }
        case 5 -> ledger.creditApplied(who, amount, seq, 0);
        case 6 -> ledger.holdStarted(who, amount, seq);
        case 7 -> ledger.holdFinished(who, amount, seq - rnd.nextInt(5), rnd.nextBoolean(), 0);
        case 8 -> ledger.resynced(who, rnd.nextInt(2_000), seq);
        case 9 -> {
          List<Delta> batch = ledger.takePending();
          ledger.inFlight(batch, seq);
          if (rnd.nextBoolean()) {
            ledger.flushCompleted(seq, 0);
          } else {
            ledger.flushFailed(seq, batch);
          }
        }
        case 10 -> ledger.markOffline(who, 0);
        default -> ledger.reattach(who, "x");
      }
      for (UUID id : ids) {
        assertEquals(ledger.balance(id), ledger.snapshot(id), "passo " + step);
      }
    }
  }

  @Test
  void evictedAccountLeavesTheMirror() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    assertEquals(OptionalLong.of(100), ledger.snapshot(alice));
    ledger.markOffline(alice, 0);
    assertEquals(OptionalLong.empty(), ledger.snapshot(alice));
  }

  // ---- saída do cache: só quando não deve nada ao banco ----

  @Test
  void offlineEntryWhosePendingNetsToZeroIsEvicted() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.deposit(alice, 5);
    Delta quit = ledger.takePending(alice).orElseThrow();
    ledger.inFlight(List.of(quit), 3);
    ledger.markOffline(alice, 0);
    ledger.flushFailed(3, List.of(quit)); // +5 volta ao pendente
    ledger.withdraw(alice, 5); // pendente zera sem flush
    ledger.evictIdleOffline(0);
    assertFalse(ledger.contains(alice));
  }

  @Test
  void accountWithoutWritesLeavesImmediately() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.markOffline(alice, 0);
    assertFalse(ledger.contains(alice));
  }

  @Test
  void accountStaysCachedUntilItsLastFlushIsConfirmed() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.withdraw(alice, 60);
    ledger.inFlight(List.of(ledger.takePending(alice).orElseThrow()), 3);
    ledger.markOffline(alice, 0);
    assertTrue(ledger.contains(alice));
    ledger.flushCompleted(3, 0);
    assertFalse(ledger.contains(alice));
  }

  @Test
  void failedFlushAfterQuitReturnsToTheSameEntry() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.withdraw(alice, 60);
    Delta quit = ledger.takePending(alice).orElseThrow();
    ledger.inFlight(List.of(quit), 3);
    ledger.markOffline(alice, 0);
    assertEquals(List.of(), ledger.flushFailed(3, List.of(quit)));
    assertTrue(ledger.contains(alice));
    assertEquals(List.of(new Delta(alice, -60)), ledger.takePending());
  }

  @Test
  void rejoinBeforeTheFlushReusesTheCachedEntry() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.withdraw(alice, 60);
    ledger.inFlight(List.of(ledger.takePending(alice).orElseThrow()), 3);
    ledger.markOffline(alice, 0);
    assertTrue(ledger.reattach(alice, "alice"));
    assertEquals(40, balance(alice));
    ledger.flushCompleted(3, 0);
    assertTrue(ledger.contains(alice), "online de novo: não sai do cache");
  }

  @Test
  void readOlderThanTheLastWriteOfThePreviousSessionIsRefused() {
    ledger.loaded(alice, "alice", 100, 1, 0);
    ledger.withdraw(alice, 60);
    ledger.inFlight(List.of(ledger.takePending(alice).orElseThrow()), 5);
    ledger.markOffline(alice, 0);
    ledger.flushCompleted(5, 0);
    // pré-login da reconexão leu no seq 4, antes do flush do quit
    assertFalse(ledger.loaded(alice, "alice", 100, 4, 0));
    assertFalse(ledger.contains(alice));
    assertTrue(ledger.loaded(alice, "alice", 40, 6, 0));
    assertEquals(40, balance(alice));
  }

  // ---- créditos externos ----

  @Test
  void externalCreditAfterTheLoadIsAddedToTheCache() {
    ledger.loaded(alice, "alice", 100, 5, 0);
    ledger.creditApplied(alice, 40, 6, 0);
    assertEquals(140, balance(alice));
  }

  @Test
  void externalCreditBeforeTheLoadWasAlreadyReadAndIsNotCountedTwice() {
    ledger.loaded(alice, "alice", 140, 6, 0);
    ledger.creditApplied(alice, 40, 5, 0);
    assertEquals(140, balance(alice));
  }

  @Test
  void externalCreditThatFinishesBeforeTheJoinIsReplayedIfTheLoadMissedIt() {
    ledger.creditApplied(alice, 40, 6, 0);
    ledger.loaded(alice, "alice", 100, 5, 0);
    assertEquals(140, balance(alice));
  }

  @Test
  void orphanThatTheLoadAlreadySawIsNotReplayed() {
    ledger.creditApplied(alice, 40, 4, 0);
    ledger.loaded(alice, "alice", 140, 5, 0);
    assertEquals(140, balance(alice));
  }

  @Test
  void orphansExpire() {
    ledger.creditApplied(alice, 40, 6, 1_000);
    ledger.prune(1_000 + 60_001, 60_000);
    ledger.loaded(alice, "alice", 100, 5, 0);
    assertEquals(100, balance(alice));
  }

  // ---- retiradas externas: reserva pessimista ----

  @Test
  void withdrawalInFlightIsAlreadyDeductedFromALoadThatMissedIt() {
    ledger.holdStarted(bob, 100, 6);
    ledger.loaded(bob, "bob", 100, 5, 0);
    assertEquals(0, balance(bob));
    assertEquals(Outcome.INSUFFICIENT, ledger.withdraw(bob, 1));
    ledger.holdFinished(bob, 100, 6, true, 0);
    assertEquals(0, balance(bob));
  }

  @Test
  void refusedWithdrawalReleasesTheReservation() {
    ledger.holdStarted(bob, 100, 6);
    ledger.loaded(bob, "bob", 100, 5, 0);
    ledger.holdFinished(bob, 100, 6, false, 0);
    assertEquals(100, balance(bob));
  }

  /** Seed 832 do fuzz da revisão: crédito e retirada em voo, a leitura não viu nenhum dos dois. */
  @Test
  void withdrawalLargerThanTheReadIsDeductedInFullWhenACreditArrivesLater() {
    ledger.holdStarted(bob, 77, 6); // banco: 61 → +43 (seq 5) → -77 (seq 6) = 27
    ledger.loaded(bob, "bob", 61, 4, 0);
    assertEquals(0, balance(bob), "exibição nunca negativa");
    assertEquals(Outcome.INSUFFICIENT, ledger.withdraw(bob, 1));
    ledger.creditApplied(bob, 43, 5, 0);
    ledger.holdFinished(bob, 77, 6, true, 0);
    assertEquals(27, balance(bob));
  }

  /** Re-revisão do PR #1 (I1): set sobre saldo descontado virava dinheiro na recusa. */
  @Test
  void setIsRefusedWhileAWithdrawalIsPending() {
    ledger.holdStarted(bob, 500, 6);
    ledger.loaded(bob, "bob", 100, 5, 0); // interno -400
    assertEquals(Outcome.BUSY, ledger.set(bob, 0));
    ledger.holdFinished(bob, 500, 6, false, 0);
    assertEquals(100, balance(bob));
    assertEquals(Outcome.OK, ledger.set(bob, 0));
    assertEquals(0, balance(bob));
  }

  /** Re-revisão do PR #1 (M-a): o teto vale contra o saldo se a reserva for recusada. */
  @Test
  void maxBalanceHoldsAgainstAPendingWithdrawalThatMayBeRefused() {
    AccountLedger small = new AccountLedger(1000);
    small.holdStarted(bob, 900, 6);
    small.loaded(bob, "bob", 100, 5, 0); // interno -800
    small.loaded(alice, "alice", 1000, 7, 0);
    assertEquals(Outcome.EXCEEDS_MAX, small.deposit(bob, 1000));
    assertEquals(Outcome.EXCEEDS_MAX, small.transfer(alice, bob, 1000));
    assertEquals(Outcome.OK, small.deposit(bob, 900));
    small.holdFinished(bob, 900, 6, false, 0);
    assertEquals(1000, small.balance(bob).orElseThrow());
  }

  @Test
  void withdrawalTheLoadAlreadySawIsNotDeductedTwice() {
    ledger.holdStarted(bob, 100, 4);
    ledger.loaded(bob, "bob", 0, 5, 0);
    ledger.holdFinished(bob, 100, 4, true, 0);
    assertEquals(0, balance(bob));
  }

  @Test
  void withdrawalFinishedBeforeTheJoinIsReplayedAsNegativeOrphan() {
    ledger.holdStarted(bob, 30, 6);
    ledger.holdFinished(bob, 30, 6, true, 0);
    ledger.loaded(bob, "bob", 100, 5, 0);
    assertEquals(70, balance(bob));
  }

  // ---- resync ----

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
    ledger.flushCompleted(3, 0);
    ledger.resynced(alice, 110, 4);
    assertEquals(110, balance(alice));
  }
}
