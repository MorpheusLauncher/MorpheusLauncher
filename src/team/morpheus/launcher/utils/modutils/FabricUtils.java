package team.morpheus.launcher.utils.modutils;

import team.morpheus.launcher.Main;
import team.morpheus.launcher.utils.thread.DownloadFileTask;
import team.morpheus.launcher.utils.thread.ParallelTasks;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

public class FabricUtils {

    public static void doFabricSetup(String mcVersion, File jsonFile) throws MalformedURLException, InterruptedException {
        String[] split = mcVersion.split("-");
        ParallelTasks tasks = new ParallelTasks();
        tasks.add(new DownloadFileTask(new URL(String.format("%s/loader/%s/%s/profile/json", Main.getFabricVersionsURL(), split[3], split[2])), jsonFile.getPath()));
        tasks.go();
    }
}
