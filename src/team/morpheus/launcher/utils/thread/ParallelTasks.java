package team.morpheus.launcher.utils.thread;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class ParallelTasks {

    private final Collection<Runnable> tasks = new ArrayList<>();

    private BiConsumer<Integer, Integer> onProgress;

    public void add(final Runnable task) {
        tasks.add(task);
    }

    public void go() throws InterruptedException {
        final int total = tasks.size();
        final AtomicInteger completed = new AtomicInteger(0);
        final ExecutorService threads = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        try {
            final CountDownLatch latch = new CountDownLatch(total);
            for (final Runnable task : tasks) {
                final Runnable wrappedTask = (task instanceof DownloadFileTask) ? new DownloadFileTask(((DownloadFileTask) task).getSource(), ((DownloadFileTask) task).getTarget(), completed, total) : task;

                threads.execute(() -> {
                    try {
                        wrappedTask.run();
                        if (task instanceof DownloadFileTask && onProgress != null) {
                            onProgress.accept(completed.get(), total);
                        }
                    } finally {
                        if (!(task instanceof DownloadFileTask)) {
                            int done = completed.incrementAndGet();
                            if (onProgress != null) onProgress.accept(done, total);
                        }
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } finally {
            threads.shutdown();
        }
    }
}