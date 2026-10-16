package dev.austv.dreamcoins.command;

import dev.austv.dreamcoins.account.AccountLedger.Outcome;
import dev.austv.dreamcoins.account.AccountService;
import dev.austv.dreamcoins.admin.AdminOperations;
import dev.austv.dreamcoins.admin.AdminOperations.Result;
import dev.austv.dreamcoins.config.Messages;
import dev.austv.dreamcoins.money.CoinType;
import dev.austv.dreamcoins.money.Money;
import dev.austv.dreamcoins.money.MoneyParser.ParseResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * {@code /moedas give|take|set <jogador> <quantia>} e {@code /moedas reload}.
 *
 * <p>Toda alteração sai no console com quem fez, o quê, para quem e o resultado — é dinheiro
 * criado ou destruído por fora da economia, e "quem me deu isto?" é pergunta de suporte.
 */
public final class AdminCommand implements TabExecutor {

  static final String PERMISSION = "dreamcoins.admin";
  private static final List<String> SUBCOMMANDS = List.of("give", "take", "set", "reload");

  private record Target(UUID uuid, String name) {}

  private final AccountService accounts;
  private final AdminOperations admin;
  private final Messages messages;
  private final Logger logger;
  /** Recarrega config e mensagens; devolve {@code true} se algo só vale depois de reiniciar. */
  private final BooleanSupplier reloader;

  public AdminCommand(
      AccountService accounts, Messages messages, Logger logger, BooleanSupplier reloader) {
    this.accounts = accounts;
    this.admin = new AdminOperations(accounts);
    this.messages = messages;
    this.logger = logger;
    this.reloader = reloader;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission(PERMISSION)) {
      messages.send(sender, "errors.no-permission");
      return true;
    }
    String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
    switch (sub) {
      case "reload" -> reload(sender);
      case "give", "take", "set" -> {
        if (args.length < 3) {
          messages.send(sender, "admin.usage");
          return true;
        }
        change(sender, sub, args);
      }
      default -> messages.send(sender, "admin.usage");
    }
    return true;
  }

  private void reload(CommandSender sender) {
    boolean needsRestart = reloader.getAsBoolean();
    messages.send(sender, "admin.reloaded");
    if (needsRestart) {
      messages.send(sender, "admin.reload-restart");
    }
    logger.info("[moedas] " + sender.getName() + " recarregou a configuração.");
  }

  private void change(CommandSender sender, String sub, String[] args) {
    ParseResult parsed = AmountArgs.parse(args, 2, sub.equals("set"));
    if (parsed instanceof ParseResult.Failure failure) {
      messages.send(
          sender,
          AmountArgs.messageKey(failure.error()),
          Placeholder.unparsed("token", failure.token()));
      return;
    }
    long amount = ((ParseResult.Success) parsed).money().totalBronze();
    String name = args[1];
    resolve(name)
        .thenCompose(
            target -> {
              if (target.isEmpty()) {
                messages.send(
                    sender, "errors.unknown-player", Placeholder.unparsed("player", name));
                return CompletableFuture.<Void>completedFuture(null);
              }
              return run(sub, target.get(), amount)
                  .thenAccept(result -> report(sender, sub, target.get(), amount, result));
            })
        .whenComplete(
            (ignored, error) -> {
              if (error != null) {
                logger.log(
                    Level.WARNING,
                    "/moedas " + sub + " " + name + " de " + sender.getName() + " falhou",
                    error);
                messages.send(sender, "errors.database");
              }
            });
  }

  private CompletableFuture<Result> run(String sub, Target target, long amount) {
    return switch (sub) {
      case "give" -> admin.give(target.uuid(), amount);
      case "take" -> admin.take(target.uuid(), amount);
      default -> admin.set(target.uuid(), amount);
    };
  }

  /** Online pelo nome exato; senão o último que entrou com o nome. Completa na main thread. */
  private CompletableFuture<Optional<Target>> resolve(String name) {
    Player online = Bukkit.getPlayerExact(name);
    if (online != null) {
      return CompletableFuture.completedFuture(
          Optional.of(new Target(online.getUniqueId(), online.getName())));
    }
    return accounts
        .lookup(name)
        .thenApply(found -> found.map(view -> new Target(view.uuid(), view.name())));
  }

  private void report(CommandSender sender, String sub, Target target, long amount, Result result) {
    logger.info(
        "[moedas] "
            + sender.getName()
            + " "
            + sub
            + " "
            + amount
            + " bronze -> "
            + target.name()
            + " ("
            + target.uuid()
            + "): "
            + result.outcome()
            + (result.drifted() ? " (saldo mudou no meio)" : "")
            + ", saldo "
            + result.balance());
    TagResolver player = Placeholder.unparsed("player", target.name());
    TagResolver amountTag = Placeholder.unparsed("amount", format(amount));
    TagResolver balanceTag = Placeholder.unparsed("balance", format(result.balance()));
    switch (result.outcome()) {
      case OK -> {
        String key = result.drifted() ? "admin.set-drift" : "admin." + sub;
        messages.send(sender, key, player, amountTag, balanceTag);
        notifyTarget(sub, target, amountTag, balanceTag);
      }
      case INSUFFICIENT -> messages.send(sender, "admin.insufficient", player, balanceTag);
      case EXCEEDS_MAX ->
          messages.send(
              sender,
              "admin.exceeds-max",
              player,
              Placeholder.unparsed("max", format(accounts.maxBalance())));
      case BUSY -> messages.send(sender, "admin.busy", player);
      case NO_ACCOUNT -> messages.send(sender, "errors.unknown-player", player);
      case UNAVAILABLE -> messages.send(sender, "errors.database");
    }
  }

  private void notifyTarget(
      String sub,
      Target target,
      TagResolver amount,
      TagResolver balance) {
    Player online = Bukkit.getPlayer(target.uuid());
    if (online == null) {
      return;
    }
    String key =
        switch (sub) {
          case "give" -> "notify.given";
          case "take" -> "notify.taken";
          default -> "notify.set";
        };
    messages.send(online, key, amount, balance);
  }

  private String format(long bronze) {
    return messages.formatter().format(Money.ofBronze(bronze));
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission(PERMISSION)) {
      return List.of();
    }
    if (args.length == 1) {
      return startingWith(SUBCOMMANDS, args[0]);
    }
    if (args[0].equalsIgnoreCase("reload")) {
      return List.of();
    }
    if (args.length == 2) {
      return startingWith(
          Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
    }
    return suggestAmount(args);
  }

  /** Depois de um número, os nomes das moedas; senão, um número de exemplo. */
  static List<String> suggestAmount(String[] args) {
    String previous = args[args.length - 2];
    if (previous.chars().allMatch(Character::isDigit) && !previous.isEmpty()) {
      List<String> coins = new ArrayList<>();
      for (CoinType type : CoinType.values()) {
        coins.add(type.aliases().getFirst());
      }
      return startingWith(coins, args[args.length - 1]);
    }
    return args[args.length - 1].isEmpty() ? List.of("1") : List.of();
  }

  private static List<String> startingWith(List<String> options, String prefix) {
    String lower = prefix.toLowerCase(Locale.ROOT);
    return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
  }
}
