package dev.austv.dreamcoins.storage;

import java.util.Locale;
import java.util.Optional;

/** O {@code storage.type} do config. */
public enum StorageType {
  SQLITE,
  MYSQL;

  public static Optional<StorageType> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "sqlite" -> Optional.of(SQLITE);
      case "mysql", "mariadb" -> Optional.of(MYSQL);
      default -> Optional.empty();
    };
  }
}
