package dev.austv.dreamcoins.storage;

import java.util.UUID;

/** Uma conta como está no banco. */
public record StoredAccount(UUID uuid, String name, long balance) {}
