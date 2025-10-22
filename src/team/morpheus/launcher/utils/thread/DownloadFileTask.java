package team.morpheus.launcher.utils.thread;

import team.morpheus.launcher.logging.MyLogger;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class DownloadFileTask implements Runnable {

    private static final MyLogger log = new MyLogger(DownloadFileTask.class);

    private final URL source;
    private final String target;

    public DownloadFileTask(URL source, String target) {
        this.source = source;
        this.target = target;
    }

    @Override
    public void run() {
        try (InputStream in = source.openStream()) {
            Files.copy(in, Paths.get(target), StandardCopyOption.REPLACE_EXISTING);
            log.info(String.format("Downloaded: %s from: %s", target, source));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}