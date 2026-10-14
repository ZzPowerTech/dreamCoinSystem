package dev.austv.dreamcoins.storage;

import dev.austv.dreamcoins.account.AccountLedger.Outcome;

/** Resultado de {@link Storage#applyGuarded}: o veredito e o saldo depois dele. */
public record GuardedResult(Outcome outcome, long balance) {}
