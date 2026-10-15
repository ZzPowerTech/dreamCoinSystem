package dev.austv.dreamcoins.account;

/**
 * Uma operação bloqueante não rodou: o serviço está desligado, o banco está travado (disjuntor
 * aberto), ou a operação esperou demais e foi <b>abandonada antes de começar</b>. Em todos os casos
 * a garantia é a mesma — <b>nada foi alterado</b> —, e é ela que deixa o Vault responder falha sem
 * mentir.
 */
public final class DatabaseUnavailableException extends Exception {

  public DatabaseUnavailableException(String message) {
    super(message);
  }
}
