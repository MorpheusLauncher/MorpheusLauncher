package team.morpheus.launcher.utils.thread;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ParallelTasks {

    private final Collection<Runnable> tasks = new ArrayList<Runnable>();

    public void add(final Runnable task) {
        tasks.add(task);
    }

    public void go() throws InterruptedException {
        final ExecutorService threads = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try {
            final CountDownLatch latch = new CountDownLatch(tasks.size());
            for (final Runnable task : tasks)
                threads.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        latch.countDown();
                    }
                });
            latch.await();
        } finally {
            threads.shutdown();
        }
    }
}