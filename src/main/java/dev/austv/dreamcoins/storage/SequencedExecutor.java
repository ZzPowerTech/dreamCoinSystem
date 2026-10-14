package dev.austv.dreamcoins.storage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A thread única do banco, com um número de sequência por tarefa.
 *
 * <p>Uma thread só é a garantia que o {@code AccountLedger} usa: as tarefas rodam na ordem de
 * submissão, então uma leitura de sequência {@code s} viu toda escrita anterior a {@code s} e
 * nenhuma posterior. O {@code synchronized} do {@link #submit} faz a sequência e a posição na fila
 * nascerem juntas mesmo quando a submissão vem de threads diferentes (o pré-login é assíncrono).
 */
public final class SequencedExecutor implements AutoCloseable {

  /** Uma tarefa de banco que pode lançar {@link java.sql.SQLException}. */
  @FunctionalInterface
  public interface Task<T> {
    T call() throws Exception;
  }

  /** A tarefa submetida: a sequência dela e o resultado, completado na thread do banco. */
  public record Submitted<T>(long seq, CompletableFuture<T> future) {}

  private final ExecutorService executor;
  private long nextSeq;

  public SequencedExecutor(String threadName) {
    this.executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, threadName);
              thread.setDaemon(true);
              return thread;
            });
  }

  public synchronized <T> Submitted<T> submit(Task<T> task) {
    long seq = ++nextSeq;
    CompletableFuture<T> future = new CompletableFuture<>();
    executor.execute(
        () -> {
          try {
            future.complete(task.call());
          } catch (Throwable error) {
            future.completeExceptionally(error);
          }
        });
    return new Submitted<>(seq, future);
  }

  /** Para de aceitar tarefas e espera as já enfileiradas terminarem, até 15 s. */
  @Override
  public void close() {
    executor.shutdown();
    try {
      executor.awaitTermination(15, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
