package dev.austv.dreamcoins.command;

import dev.austv.dreamcoins.account.AccountService;
import dev.austv.dreamcoins.config.Messages;
import dev.austv.dreamcoins.money.Money;
import dev.austv.dreamcoins.money.MoneyParser.ParseResult;
import dev.austv.dreamcoins.payment.PaymentService;
import dev.austv.dreamcoins.payment.PaymentService.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * {@code /pagar <jogador> <quantia>} e {@code /pagar confirmar}. A quantia aceita as três moedas
 * misturadas: {@code /pagar whitefox 1 ouro 35 bronze}, {@code /pagar whitefox 2p 1b}.
 *
 * <p>Também é o {@link Listener} que descarta o pedido à espera de confirmação na saída.
 */
public final class PayCommand implements TabExecutor, Listener {

  static final String PERMISSION = "dreamcoins.pay";
  static final String BYPASS_COOLDOWN = "dreamcoins.bypass.cooldown";
  static final String BYPASS_CONFIRM = "dreamcoins.bypass.confirm";
  private static final String CONFIRM = "confirmar";

  private record Target(UUID uuid, String name) {}

  private final AccountService accounts;
  private final PaymentService payments;
  private final Messages messages;
  private final Logger logger;

  public PayCommand(
      AccountService accounts, PaymentService payments, Messages messages, Logger logger) {
    this.accounts = accounts;
    this.payments = payments;
    this.messages = messages;
    this.logger = logger;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player payer)) {
      messages.send(sender, "errors.player-only");
      return true;
    }
    if (!payer.hasPermission(PERMISSION)) {
      messages.send(payer, "errors.no-permission");
      return true;
    }
    boolean bypassCooldown = payer.hasPermission(BYPASS_COOLDOWN);
    if (args.length == 1 && args[0].equalsIgnoreCase(CONFIRM)) {
      deliver(payer, payments.confirm(payer.getUniqueId(), bypassCooldown));
      return true;
    }
    if (args.length < 2) {
      messages.send(payer, "usage.pagar");
      return true;
    }
    ParseResult parsed = AmountArgs.parse(args, 1, false);
    if (parsed instanceof ParseResult.Failure failure) {
      messages.send(
          payer,
          AmountArgs.messageKey(failure.error()),
          Placeholder.unparsed("token", failure.token()));
      return true;
    }
    long amount = ((ParseResult.Success) parsed).money().totalBronze();
    boolean bypassConfirm = payer.hasPermission(BYPASS_CONFIRM);
    String name = args[0];
    CompletableFuture<Result> payment =
        resolve(name)
            .thenCompose(
                target -> {
                  if (target.isEmpty()) {
                    messages.send(
                        payer, "errors.unknown-player", Placeholder.unparsed("player", name));
                    return CompletableFuture.completedFuture(null);
                  }
                  return payments.request(
                      payer.getUniqueId(),
                      target.get().uuid(),
                      target.get().name(),
                      amount,
                      bypassCooldown,
                      bypassConfirm);
                });
    deliver(payer, payment);
    return true;
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

  private void deliver(Player payer, CompletableFuture<Result> payment) {
    payment.whenComplete(
        (result, error) -> {
          if (error != null) {
            logger.log(Level.WARNING, "/pagar de " + payer.getName() + " falhou", error);
            messages.send(payer, "errors.database");
            return;
          }
          if (result != null) {
            report(payer, result);
          }
        });
  }

  private void report(Player payer, Result result) {
    TagResolver player = Placeholder.unparsed("player", nameOf(result));
    TagResolver amount = Placeholder.unparsed("amount", format(result.amount()));
    TagResolver balance = Placeholder.unparsed("balance", format(result.payerBalance()));
    switch (result.status()) {
      case PAID -> {
        logger.info(
            "[pagar] "
                + payer.getName()
                + " -> "
                + nameOf(result)
                + " ("
                + result.target()
                + "): "
                + result.amount()
                + " bronze");
        messages.send(payer, "pay.paid", player, amount, balance);
        Player receiver = Bukkit.getPlayer(result.target());
        if (receiver != null) {
          messages.send(
              receiver,
              "pay.received",
              amount,
              Placeholder.unparsed("player", payer.getName()),
              Placeholder.unparsed(
                  "balance", format(accounts.balance(result.target()).orElse(0))));
        }
      }
      case NEEDS_CONFIRMATION ->
          messages.send(
              payer, "pay.confirm", player, amount, seconds("seconds", result.detail()));
      case SELF -> messages.send(payer, "pay.self");
      case BELOW_MIN ->
          messages.send(
              payer, "pay.below-min", Placeholder.unparsed("min", format(result.detail())));
      case ABOVE_MAX ->
          messages.send(
              payer, "pay.above-max", Placeholder.unparsed("max", format(result.detail())));
      case COOLDOWN -> messages.send(payer, "pay.cooldown", seconds("seconds", result.detail()));
      case OFFLINE_NOT_ALLOWED -> messages.send(payer, "pay.offline-not-allowed", player);
      case INSUFFICIENT -> messages.send(payer, "pay.insufficient", balance);
      case RECEIVER_FULL -> messages.send(payer, "pay.receiver-full", player);
      case UNKNOWN_TARGET -> messages.send(payer, "errors.unknown-player", player);
      case PAYER_NOT_LOADED -> messages.send(payer, "errors.account-loading");
      case BUSY -> messages.send(payer, "pay.busy");
      case UNAVAILABLE -> messages.send(payer, "errors.database");
      case NO_PENDING -> messages.send(payer, "pay.no-pending");
    }
  }

  private static String nameOf(Result result) {
    return result.targetName() == null ? "?" : result.targetName();
  }

  /** Milissegundos → segundos, arredondando para cima: "espere 0 s" não ajuda ninguém. */
  private static TagResolver seconds(String key, long millis) {
    return Placeholder.unparsed(key, Long.toString((millis + 999) / 1000));
  }

  private String format(long bronze) {
    return messages.formatter().format(Money.ofBronze(bronze));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    payments.forget(event.getPlayer().getUniqueId());
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission(PERMISSION)) {
      return List.of();
    }
    if (args.length == 1) {
      List<String> options = new ArrayList<>();
      options.add(CONFIRM);
      for (Player online : Bukkit.getOnlinePlayers()) {
        if (!online.equals(sender)) {
          options.add(online.getName());
        }
      }
      String prefix = args[0].toLowerCase(Locale.ROOT);
      return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    if (args[0].equalsIgnoreCase(CONFIRM)) {
      return List.of();
    }
    return AdminCommand.suggestAmount(args);
  }
}
