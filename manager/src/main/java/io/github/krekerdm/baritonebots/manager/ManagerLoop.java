package io.github.krekerdm.baritonebots.manager;

import io.github.krekerdm.baritonebots.manager.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one thread that owns all manager state (SPEC §5.1). IO threads post work here; HTTP handlers
 * use {@link #await(Callable)}, which gives up after 10 s. Code running on the loop must never block.
 */
public final class ManagerLoop {
    public static final long AWAIT_TIMEOUT_MS = 10_000;

    private final ScheduledExecutorService exec;
    private volatile Thread thread;

    public ManagerLoop() {
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "manager-loop");
            t.setDaemon(true);
            thread = t;
            return t;
        });
    }

    public boolean inLoop() {
        return Thread.currentThread() == thread;
    }

    /** Throws when called from another thread; guards loop-owned state in debug paths. */
    public void assertInLoop() {
        if (!inLoop()) {
            throw new IllegalStateException("must run on the manager loop, not " + Thread.currentThread().getName());
        }
    }

    public void post(Runnable task) {
        try {
            exec.execute(guard(task));
        } catch (RejectedExecutionException e) {
            Log.warn("loop stopped; dropped task %s", task);
        }
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        if (inLoop()) {
            complete(f, task);
            return f;
        }
        try {
            exec.execute(() -> complete(f, task));
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    private static <T> void complete(CompletableFuture<T> f, Callable<T> task) {
        try {
            f.complete(task.call());
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
    }

    /**
     * Runs {@code task} on the loop and waits for its result. Exceptions thrown by the task are rethrown
     * unchanged (unchecked) so HTTP error mapping sees the original type.
     *
     * @throws LoopTimeoutException after {@link #AWAIT_TIMEOUT_MS}
     */
    public <T> T await(Callable<T> task) {
        if (inLoop()) {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        try {
            return submit(task).get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new LoopTimeoutException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LoopTimeoutException();
        }
    }

    public void awaitRun(Runnable task) {
        await(() -> {
            task.run();
            return null;
        });
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return exec.schedule(guard(task), Math.max(0, delay), unit);
    }

    public ScheduledFuture<?> every(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return exec.scheduleWithFixedDelay(guard(task), initialDelay, period, unit);
    }

    private static Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                Log.error("loop task failed: " + task, t);
            }
        };
    }

    public void shutdown(long waitMs) {
        exec.shutdown();
        try {
            exec.awaitTermination(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The loop did not answer in time (it is overloaded or stuck). HTTP maps this to 503. */
    public static final class LoopTimeoutException extends RuntimeException {
        public LoopTimeoutException() {
            super("manager loop did not answer within " + AWAIT_TIMEOUT_MS + " ms");
        }
    }
}
