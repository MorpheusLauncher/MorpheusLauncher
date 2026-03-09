package team.morpheus.launcher.utils.thread;

import lombok.Getter;
import team.morpheus.launcher.logging.MyLogger;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadFileTask implements Runnable {

    private static final MyLogger log = new MyLogger(DownloadFileTask.class);

    @Getter
    private final URL source;
    @Getter
    private final String target;
    private final AtomicInteger completed;
    private final int total;

    /**
     * Costruttore con contatore
     */
    public DownloadFileTask(URL source, String target, AtomicInteger completed, int total) {
        this.source = source;
        this.target = target;
        this.completed = completed;
        this.total = total;
    }

    /**
     * Costruttore legacy senza contatore
     */
    public DownloadFileTask(URL source, String target) {
        this(source, target, null, 0);
    }

    @Override
    public void run() {
        try (InputStream in = source.openStream()) {
            Files.copy(in, Paths.get(target), StandardCopyOption.REPLACE_EXISTING);
            String progress = (completed != null) ? String.format("[%d/%d] ", completed.incrementAndGet(), total) : "";
            log.info(String.format("%sDownloaded: %s from: %s", progress, target, source));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}