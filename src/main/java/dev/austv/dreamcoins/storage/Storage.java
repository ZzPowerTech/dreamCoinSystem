package dev.austv.dreamcoins.storage;

import dev.austv.dreamcoins.account.AccountLedger.Delta;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Persistência das contas. <b>Bloqueante</b>: só é chamada pela thread do executor do banco.
 *
 * <p>Toda alteração de saldo é um <b>delta</b>, nunca um valor absoluto. Com dois servidores no
 * mesmo MySQL, cada um somando a sua parte, nada se perde; gravar o saldo inteiro faria o último a
 * escrever apagar o que o outro gravou.
 */
public interface Storage extends AutoCloseable {

  /** Abre o pool e cria as tabelas que faltarem. */
  void open() throws SQLException;

  /** Lê a conta, criando-a com {@code startingBalance} se não existir; atualiza o nome. */
  long loadOrCreate(UUID uuid, String name, long startingBalance) throws SQLException;

  /**
   * Grava os deltas do cache numa transação só — ou todos, ou nenhum. O saldo resultante nunca fica
   * negativo: o cache já validou, então isso só acontece com escrita concorrente de outro servidor,
   * e aí o saldo é zerado.
   *
   * @return o que <b>não</b> pôde ser aplicado (o resto além do zero, ou o delta inteiro de conta
   *     que não existe mais) — dinheiro gasto que o banco não tinha; quem chama precisa registrar
   */
  List<Delta> applyDeltas(List<Delta> deltas) throws SQLException;

  /**
   * Alteração validada no próprio banco, para contas fora do cache: só aplica se o resultado ficar
   * entre 0 e {@code maxBalance}.
   */
  GuardedResult applyGuarded(UUID uuid, long delta, long maxBalance) throws SQLException;

  OptionalLong balance(UUID uuid) throws SQLException;

  /** Busca por nome sem diferenciar maiúsculas; o mais recente se houver repetição. */
  Optional<StoredAccount> findByName(String name) throws SQLException;

  /** Saldo das contas pedidas; as inexistentes ficam de fora do mapa. */
  Map<UUID, Long> balances(Collection<UUID> uuids) throws SQLException;

  @Override
  void close();
}
