package dev.austv.dreamcoins.command;

import dev.austv.dreamcoins.account.AccountService;
import dev.austv.dreamcoins.config.Messages;
import dev.austv.dreamcoins.money.Money;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /saldo [jogador]}. */
public final class BalanceCommand implements TabExecutor {

  static final String PERMISSION = "dreamcoins.balance";
  static final String PERMISSION_OTHERS = "dreamcoins.balance.others";

  private final AccountService accounts;
  private final Messages messages;
  private final Logger logger;

  public BalanceCommand(AccountService accounts, Messages messages, Logger logger) {
    this.accounts = accounts;
    this.messages = messages;
    this.logger = logger;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission(PERMISSION)) {
      messages.send(sender, "errors.no-permission");
      return true;
    }
    if (args.length == 0) {
      showOwn(sender);
      return true;
    }
    if (args.length > 1) {
      messages.send(sender, "usage.saldo");
      return true;
    }
    if (sender instanceof Player player && player.getName().equalsIgnoreCase(args[0])) {
      showOwn(sender);
      return true;
    }
    if (!sender.hasPermission(PERMISSION_OTHERS)) {
      messages.send(sender, "errors.no-permission");
      return true;
    }
    showOther(sender, args[0]);
    return true;
  }

  private void showOwn(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      messages.send(sender, "usage.saldo");
      return;
    }
    accounts
        .balance(player.getUniqueId())
        .ifPresentOrElse(
            balance ->
                messages.send(
                    sender,
                    "balance.self",
                    Placeholder.unparsed("balance", format(balance))),
            () -> messages.send(sender, "errors.account-loading"));
  }

  private void showOther(CommandSender sender, String name) {
    Player online = Bukkit.getPlayerExact(name);
    if (online != null && accounts.isLoaded(online.getUniqueId())) {
      sendOther(sender, online.getName(), accounts.balance(online.getUniqueId()).getAsLong());
      return;
    }
    accounts
        .lookup(name)
        .whenComplete(
            (found, error) -> {
              if (error != null) {
                logger.log(Level.WARNING, "Falha ao consultar o saldo de " + name, error);
                messages.send(sender, "errors.database");
                return;
              }
              found.ifPresentOrElse(
                  view -> sendOther(sender, view.name(), view.balance()),
                  () ->
                      messages.send(
                          sender, "errors.unknown-player", Placeholder.unparsed("player", name)));
            });
  }

  private void sendOther(CommandSender sender, String name, long balance) {
    messages.send(
        sender,
        "balance.other",
        Placeholder.unparsed("player", name),
        Placeholder.unparsed("balance", format(balance)));
  }

  private String format(long balance) {
    return messages.formatter().format(Money.ofBronze(balance));
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String label, String[] args) {
    if (args.length != 1 || !sender.hasPermission(PERMISSION_OTHERS)) {
      return List.of();
    }
    String prefix = args[0].toLowerCase(Locale.ROOT);
    return Bukkit.getOnlinePlayers().stream()
        .map(Player::getName)
        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
        .toList();
  }
}
