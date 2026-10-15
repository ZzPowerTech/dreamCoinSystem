package dev.austv.dreamcoins.account;

/**
 * Uma operação bloqueante <b>começou</b> no banco e não terminou a tempo: diferente de {@link
 * DatabaseUnavailableException}, aqui a escrita <b>pode</b> ser gravada depois. Quem recebe esta
 * exceção não pode dizer "nada mudou" — tem de avisar que o resultado é desconhecido.
 */
public final class OutcomeUnknownException extends Exception {

  public OutcomeUnknownException(String message) {
    super(message);
  }
}
