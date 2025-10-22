package team.morpheus.launcher.starters.impl;

import sun.management.VMManagement;
import team.morpheus.launcher.model.products.MojangProduct;
import team.morpheus.launcher.starters.ILibraryManager;
import team.morpheus.launcher.utils.OSUtils;
import team.morpheus.launcher.logging.MyLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class ClasspathLauncher implements ILibraryManager {

    private final MyLogger log = new MyLogger(ClasspathLauncher.class);
    private final MojangProduct.Game game;
    private final List<URL> paths;
    private final boolean startOnFirstThread;

    public ClasspathLauncher(MojangProduct.Game game, List<URL> paths, boolean startOnFirstThread) {
        this.game = game;
        this.paths = paths;
        this.startOnFirstThread = startOnFirstThread;
    }

    @Override
    public MojangProduct.Game getGame() {
        return game;
    }

    @Override
    public List<URL> getPaths() {
        return paths;
    }

    @Override
    public void launch(List<String> gameargs) throws Exception {
        log.info("Launching game with classpath");

        StringBuilder classPath = new StringBuilder();
        /* Append all libraries to class path */
        for (URL path : getPaths()) {
            classPath.append(new File(path.toURI()).getPath()).append(OSUtils.getPlatform().equals(OSUtils.OS.windows) ? ";" : ":");
        }

        String libraryPath = System.getProperty("java.library.path");

        ProcessBuilder processBuilder = getProcessBuilder(libraryPath, classPath);
        processBuilder.command().addAll(gameargs);

        /* Start the children process (game) */
        Process process = processBuilder.start();

        /* Forward the output from children process into parent process */
        new Thread(() -> processInputStream(process)).start();
        new Thread(() -> processErrorStream(process)).start();
    }

    private ProcessBuilder getProcessBuilder(String libraryPath, StringBuilder classPath) {
        List<String> command = new ArrayList<>();
        command.add(String.format("%s/bin/java", System.getProperty("java.home")));
        if (startOnFirstThread) command.add("-XstartOnFirstThread");

        /* Java agent passtrough to children process */
        for (String agent : getActiveJavaAgents())
            command.add(agent);

        /* Specify paths of natives */
        command.add(String.format("-Djava.library.path=%s", libraryPath));
        command.add(String.format("-Djna.tmpdir=%s", libraryPath));
        command.add(String.format("-Dorg.lwjgl.system.SharedLibraryExtractPath=%s", libraryPath));
        command.add(String.format("-Dio.netty.native.workdir=%s", libraryPath));

        command.add("-cp");
        command.add(classPath.toString());
        command.add(getGame().mainClass);
        return new ProcessBuilder(command);
    }

    private void processInputStream(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) System.out.println(line);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processErrorStream(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) System.err.println(line);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<String> getActiveJavaAgents() {
        List<String> agents = new ArrayList<>();
        try {
            Field jvmField = ManagementFactory.getRuntimeMXBean().getClass().getDeclaredField("jvm");
            jvmField.setAccessible(true);
            VMManagement jvm = (VMManagement) jvmField.get(ManagementFactory.getRuntimeMXBean());
            List<String> inputArguments = jvm.getVmArguments();

            for (String arg : inputArguments) {
                if (arg.startsWith("-javaagent:") && !arg.contains("debugger-agent.jar")) {
                    log.info("Detected agent to passtrough: " + arg);
                    agents.add(arg);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return agents;
    }
}
