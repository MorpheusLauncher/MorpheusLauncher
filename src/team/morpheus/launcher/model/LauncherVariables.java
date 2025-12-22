package team.morpheus.launcher.model;

import lombok.Getter;

@Getter
public class LauncherVariables {

    private String mcVersion;
    private boolean modded;
    private boolean classPath;
    private String gamePath;
    private boolean startOnFirstThread;

    public LauncherVariables(String mcVersion, boolean modded, boolean useclasspath, String gamePath, boolean startonfirstthread) {
        this.mcVersion = mcVersion;
        this.modded = modded;
        this.classPath = useclasspath;
        this.gamePath = gamePath;
        this.startOnFirstThread = startonfirstthread;
    }

    public LauncherVariables(String gameversion, String gamePath, boolean startOnFirstThread) {
        this(gameversion, true, false, gamePath, startOnFirstThread);
    }
}