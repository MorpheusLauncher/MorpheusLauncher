package team.morpheus.launcher;

import me.lampadina.activity.Discord;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import team.morpheus.launcher.logging.MyLogger;
import team.morpheus.launcher.model.LauncherVariables;
import team.morpheus.launcher.model.products.MojangProduct;
import team.morpheus.launcher.model.products.MorpheusProduct;
import team.morpheus.launcher.starters.GameLauncher;
import team.morpheus.launcher.utils.CryptoEngine;
import team.morpheus.launcher.utils.OSUtils;
import team.morpheus.launcher.utils.Utils;
import team.morpheus.launcher.utils.VersionUtils;
import team.morpheus.launcher.utils.thread.DownloadFileTask;
import team.morpheus.launcher.utils.thread.ParallelTasks;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static team.morpheus.launcher.utils.Utils.makeDirectory;
import static team.morpheus.launcher.utils.modutils.FabricUtils.doFabricSetup;
import static team.morpheus.launcher.utils.modutils.ForgeUtils.doForgeSetup;
import static team.morpheus.launcher.utils.modutils.OptiFineUtils.doOptifineSetup;
import static team.morpheus.launcher.utils.modutils.OptiForgeUtils.doOptiForgeSetup;

public class Launcher {

    private static final MyLogger log = new MyLogger(Launcher.class);
    private JSONParser jsonParser = new JSONParser();
    public static final Environment env = new Environment();

    public Launcher(LauncherVariables variables, MorpheusProduct.Product morpheusProduct) throws Exception {
        env.setVariables(variables);
        env.setMorpheus(morpheusProduct);
    }

    public Launcher(LauncherVariables variables) throws Exception {
        env.setVariables(variables);
    }

    public void launchGame() throws Exception {
        LauncherVariables variables = env.getVariables();
        boolean isLatestVersion = false;
        try {
            /* Get all versions from mojang */
            env.setVanilla(VersionUtils.retrieveVersions());

            /* Find version by name gave by user */
            String targetName = variables.getMcVersion();

            if (variables.getMcVersion().equalsIgnoreCase("latest")) {
                targetName = env.getVanilla().latest.release;
                isLatestVersion = true;
            }
            if (variables.getMcVersion().equalsIgnoreCase("snapshot")) {
                targetName = env.getVanilla().latest.snapshot;
                isLatestVersion = true;
            }

            env.setTarget(VersionUtils.findVersion(env.getVanilla(), targetName));
        } catch (Exception e) {
            log.error("Cannot download/parse mojang versions json");
        }

        // Make .minecraft/
        env.setGameFolder(makeDirectory(variables.getGamePath()));

        // Make .minecraft/assets/
        env.setAssetsFolder(makeDirectory(String.format("%s/assets", env.getGameFolder().getPath())));

        // Make .minecraft/versions/<gameVersion>
        File versionPath = makeDirectory(String.format("%s/versions/%s", env.getGameFolder().getPath(), variables.getMcVersion()));

        // Download json to .minecraft/versions/<gameVersion>/<gameVersion.json
        File jsonFile = new File(String.format("%s/%s.json", versionPath.getPath(), variables.getMcVersion()));
        if (env.getTarget() != null && env.getTarget().url != null) {
            /* Extract json file hash from download url */
            String jsonHash = env.getTarget().url.substring(env.getTarget().url.lastIndexOf("/") - 40, env.getTarget().url.lastIndexOf("/"));

            /* if the json doesn't exist or its hash is invalidated, download from mojang repo */
            /* isLatestVersion is put to skip sha check when "latest" or "snapshot" is used */
            if (!jsonFile.exists() || jsonFile.exists() && !jsonHash.equals(CryptoEngine.fileHash(jsonFile, "SHA-1")) || isLatestVersion) {
                ParallelTasks tasks = new ParallelTasks();
                tasks.add(new DownloadFileTask(new URL(env.getTarget().url), jsonFile.getPath()));
                tasks.go();
            }

            /* overwrites id field in json to get better recognition by gui */
            if (isLatestVersion) overwriteJsonId(variables.getMcVersion(), jsonFile);
        }
        String mcLowercase = variables.getMcVersion().toLowerCase();
        if (!jsonFile.exists()) {
            if (mcLowercase.contains("fabric")) {
                doFabricSetup(mcLowercase, jsonFile);
            } else if (mcLowercase.contains("optiforge")) {
                doOptiForgeSetup(mcLowercase, jsonFile);
            } else if (mcLowercase.contains("forge")) {
                doForgeSetup(mcLowercase, jsonFile);
                overwriteJsonId(variables.getMcVersion(), jsonFile);
            } else if (mcLowercase.contains("optifine")) {
                doOptifineSetup(variables.getMcVersion(), jsonFile);
            }
        }

        /* Serialize the json file to read its properties */
        env.setGame(VersionUtils.retrieveGame(jsonFile));
        /* Download vanilla jar to .minecraft/versions/<gameVersion>/<gameVersion.jar */
        File jarFile = new File(String.format("%s/%s.jar", versionPath.getPath(), variables.getMcVersion()));
        if (env.getGame().downloads != null && env.getGame().downloads.client != null) {
            String jarHash = env.getGame().downloads.client.sha1;

            /* if the vanilla jar doesn't exist or its hash is invalidated, download from mojang repo */
            if (!jarFile.exists() || jarFile.exists() && !jarHash.equals(CryptoEngine.fileHash(jarFile, "SHA-1"))) {
                ParallelTasks tasks = new ParallelTasks();
                tasks.add(new DownloadFileTask(new URL(env.getGame().downloads.client.url), jarFile.getPath()));
                tasks.go();
            }
        }

        // Make natives dir .minecraft/versions/<gameVersion>/natives/
        File nativesPath = makeDirectory(String.format("%s/natives", versionPath.getPath()));

        /* If internet is available download the parent (vanilla) version when you launch a modloader
         * Example: downloads the "1.19.2" while you launch "fabric-loader-0.14.21-1.19.2"
         * Because inside optifine, fabric or forge json there is a field called "inheritsFrom"
         * "inheritsFrom" basically describes on which vanilla version the modloader bases of */
        if (env.getGame().inheritsFrom != null) {
            if (env.getVanilla() != null)
                env.setTarget(VersionUtils.findVersion(env.getVanilla(), env.getGame().inheritsFrom));

            File inheritedVersionPath = makeDirectory(String.format("%s/versions/%s", env.getGameFolder().getPath(), env.getGame().inheritsFrom));

            /* Download the vanilla json which modloader put its basis on */
            File inheritedjsonFile = new File(String.format("%s/%s.json", inheritedVersionPath.getPath(), env.getGame().inheritsFrom));
            if (env.getTarget() != null && env.getTarget().url != null) {
                String jsonHash = env.getTarget().url.substring(env.getTarget().url.lastIndexOf("/") - 40, env.getTarget().url.lastIndexOf("/"));

                /* if the vanilla json doesn't exist or its hash is invalidated, download from mojang repo */
                if (!inheritedjsonFile.exists() || inheritedjsonFile.exists() && !jsonHash.equals(CryptoEngine.fileHash(inheritedjsonFile, "SHA-1"))) {
                    ParallelTasks tasks = new ParallelTasks();
                    tasks.add(new DownloadFileTask(new URL(env.getTarget().url), inheritedjsonFile.getPath()));
                    tasks.go();
                }
            }

            env.setInherited(VersionUtils.retrieveGame(inheritedjsonFile));

            /* Download the vanilla client jar when you launch a modloader that put its basis on it */
            File inheritedjarFile = new File(String.format("%s/%s.jar", inheritedVersionPath.getPath(), env.getGame().inheritsFrom));
            if (env.getInherited().downloads != null && env.getInherited().downloads.client != null) {
                String jarHash = env.getInherited().downloads.client.sha1;

                if (!inheritedjarFile.exists() || inheritedjarFile.exists() && !jarHash.equals(CryptoEngine.fileHash(inheritedjarFile, "SHA-1"))) {
                    ParallelTasks tasks = new ParallelTasks();
                    tasks.add(new DownloadFileTask(new URL(env.getInherited().downloads.client.url), inheritedjarFile.getPath()));
                    tasks.go();
                }
            }
        }

        /* This variable returns ALWAYS the vanilla version, even when you launch modloader */
        MojangProduct.Game vanilla = (env.getInherited() != null ? env.getInherited() : env.getGame());

        /* Download natives */
        setupNatives(vanilla, nativesPath);

        /* Download client assets */
        setupAssets(vanilla);

        /* Setup the libraries needed to load vanilla minecraft */
        List<URL> paths = new ArrayList<>();
        /* Prepare required client arguments */
        List<String> gameargs = new ArrayList<>();

        /* Prepare launching arguments for launching minecraft
         * replaces placeholders with real values */
        for (String s : argbuilder(vanilla)) {
            s = s.replace("${auth_player_name}", Main.getMojangSession().getUsername()) // player username
                    .replace("${auth_session}", "1234") // what is this?
                    .replace("${version_name}", env.getGame().id) // Version launched
                    .replace("${game_directory}", env.getGameFolder().getPath()) // Game root dir
                    .replace("${game_assets}", env.getAssetsFolder().getPath()) // Game assets root dir
                    .replace("${assets_root}", env.getAssetsFolder().getPath()) // Same as the previous one
                    .replace("${assets_index_name}", vanilla.assetIndex.id) // assets index json filename
                    .replace("${auth_uuid}", Main.getMojangSession().getUUID()) // player uuid
                    .replace("${auth_access_token}", Main.getMojangSession().getSessionToken()) // player token for premium
                    .replace("${user_type}", "msa") // type of premium auth
                    .replace("${version_type}", env.getGame().type) // type of game version, ex. release, snapshot
                    .replace("${user_properties}", "{}"); // unknown
            gameargs.add(s);
        }
        /* Append modloader launching arguments to vanilla */
        if (env.getInherited() != null) {
            for (String s : argbuilder(env.getGame())) {
                if (!gameargs.contains(s)) {
                    gameargs.add(s);
                }
            }
        }

        GameLauncher.LaunchMode launchMode = GameLauncher.LaunchMode.ClassLoader;

        /* Put the client jar to url list */
        if (Main.getVanilla() != null) {
            if (variables.isModded()) {
                paths.addAll(setupLibraries(env.getGame()));  /* Append modloader libraries if the game is modded */
                paths.addAll(setupLibraries(vanilla)); /* Append vanilla libraries */

                paths = dedupeLibraries(paths);

                if (usesJavaModules(env.getGame())) {
                    log.info("Classpath compatibility mode will not activate to prevent twice version loading");
                } else {
                    /* Due to unknown modloader reasons, we need to load even the inherited (vanilla) version */
                    jarFile = new File(String.format("%s/%s.jar", (new File(String.format("%s/versions/%s", env.getGameFolder().getPath(), vanilla.id))).getPath(), vanilla.id));

                    /* Set the java.class.path to make modloaders like forge/fabric to work */
                    makeModloaderCompatibility(paths, jarFile);
                }
            } else {
                paths.addAll(setupLibraries(vanilla)); /* Append vanilla libraries */
            }

            if (paths.add(jarFile.toURI().toURL())) log.info(String.format("Loading: %s", jarFile.toURI().toURL()));
            initDiscordRPC(buildRPCstatus(mcLowercase, vanilla.id));
            if (variables.isClassPath()) launchMode = GameLauncher.LaunchMode.ClassPath;
        } else if (Main.getMorpheus() != null) {
            paths.addAll(setupLibraries(vanilla));
            URL url;
            for (MorpheusProduct.Product.Library library : env.getMorpheus().libraries) {
                url = new URL(String.format("%s/downloads/morpheus-lite/%s.jar", Main.getMorpheusAPI(), library.name));
                if (paths.add(url)) log.info(String.format("Loading: %s", url.toURI().toURL()));
            }
            url = new URL(String.format("%s/downloads/morpheus-lite/%s.jar", Main.getMorpheusAPI(), env.getMorpheus().id));
            if (paths.add(url.toURI().toURL())) log.info(String.format("Loading: %s", url.toURI().toURL()));
            initDiscordRPC(String.format("Morpheus %s", vanilla.id));
        }

        new GameLauncher(env.getGame(), vanilla, paths, launchMode, variables.isStartOnFirstThread()).launch(gameargs);
    }

    private boolean usesJavaModules(MojangProduct.Game game) {
        if (game.arguments == null || game.arguments.jvm == null) return false;
        for (Object o : game.arguments.jvm) {
            String s = o.toString();
            if (s.contains("--add-modules") || s.contains("--add-opens") || s.contains("--add-exports") || s.equals("-p") || s.contains("--module-path"))
                return true;
        }
        return false;
    }

    private void overwriteJsonId(String mcVersion, File jsonFile) throws IOException, ParseException {
        FileReader reader = new FileReader(jsonFile);
        JSONParser jsonParser = new JSONParser();
        JSONObject jsonObject = (JSONObject) jsonParser.parse(reader);
        jsonObject.replace("id", jsonObject.get("id"), mcVersion);
        FileWriter writer = new FileWriter(jsonFile);
        writer.write(jsonObject.toJSONString());
        writer.flush();
        writer.close();
    }

    /* Workaround for some modloaders */
    private void makeModloaderCompatibility(List<URL> paths, File jarFile) throws URISyntaxException {
        StringBuilder classPath = new StringBuilder();
        for (URL path : paths) {
            classPath.append(new File(path.toURI()).getPath()).append(";");
        }
        classPath.append(new File(jarFile.toURI()).getPath());
        System.setProperty("java.class.path", classPath.toString());

        log.info("Enabled classpath compatibility mode, this is needed by modloaders to work");
    }

    /* in newer minecraft versions mojang changed how launch arguments are specified in JSON
     * This automatically choose how arguments should be managed */
    private String[] argbuilder(MojangProduct.Game game) {
        if (game.minecraftArguments != null) {
            // Legacy versions
            return game.minecraftArguments.split(" ");
        } else {
            // Recent versions
            Object[] objectArray = game.arguments.game.toArray();
            String[] stringArray = new String[objectArray.length];
            for (int i = 0; i < objectArray.length; i++) {
                stringArray[i] = String.valueOf(objectArray[i]);
            }
            return stringArray;
        }
    }

    /* This method picks libraries and put into a URL list */
    private List<URL> setupLibraries(MojangProduct.Game game) throws IOException, NoSuchAlgorithmException, InterruptedException {
        String os_arch = OSUtils.getOSArch();

        boolean isArmProcessor = (os_arch.contains("arm") || os_arch.contains("aarch"));
        boolean isRiscVProcessor = os_arch.contains("riscv64");

        boolean isMacArm = OSUtils.getPlatform() == OSUtils.OS.macos && isArmProcessor;
        boolean isLinuxArm = OSUtils.getPlatform() == OSUtils.OS.linux && isArmProcessor;
        boolean isLinuxRiscV = OSUtils.getPlatform() == OSUtils.OS.linux && isRiscVProcessor;

        List<URL> paths = new ArrayList<>();
        for (MojangProduct.Game.Library lib : game.libraries) {
            File libFolder = new File(String.format("%s/libraries", env.getGameFolder().getPath()));
            /* Resolve libraries from json links */
            if (lib.downloads != null && lib.downloads.artifact != null) {
                MojangProduct.Game.Artifact artifact = lib.downloads.artifact;

                /* Rule system, WARNING: potentially incomplete and broken */
                boolean allow = true;
                if (lib.rules != null) allow = checkRule(lib.rules);
                if (!allow) continue;

                if (artifact.path != null && !artifact.path.isEmpty()) {
                    /* Jar library local file path */
                    File file = new File(String.format("%s/%s", libFolder.getPath(), artifact.path));
                    String[] split = lib.name.split(":");
                    boolean isLwjgl33 = lib.name.contains("lwjgl") && split[2].startsWith("3.3");
                    boolean isJna = lib.name.contains("jna");
                    boolean isJavaObjcBridge = lib.name.contains("java-objc-bridge");

                    URL libUrl = null;
                    boolean isCustomLib = false;

                    if (isMacArm) {
                        if (lib.name.contains("lwjgl") && (split[2].startsWith("3.1") || split[2].startsWith("3.2"))) {
                            if (split.length == 4) {
                                file = new File(String.format("%s/org/lwjgl/%s/3.3.1/%s-3.3.1-natives-macos-arm64.jar", libFolder.getPath(), split[1], split[1]));
                                libUrl = new URL(String.format("%s/downloads/extra-libs/lwjgl-3.3.1/%s-natives-arm64.jar", Main.getMorpheusAPI(), split[1]));
                            } else if (split.length == 3) {
                                file = new File(String.format("%s/org/lwjgl/%s/3.3.1/%s-3.3.1.jar", libFolder.getPath(), split[1], split[1]));
                                libUrl = new URL(String.format("%s/downloads/extra-libs/lwjgl-3.3.1/%s.jar", Main.getMorpheusAPI(), split[1]));
                            }
                            isCustomLib = true;
                        } else {
                            if (artifact.url != null && !artifact.url.isEmpty()) libUrl = new URL(artifact.url);
                        }
                        if (isJna) {
                            // Use ARM64-compatible JNA for macOS ARM (5.18.1+ has native ARM64 support)
                            file = new File(String.format("%s/net/java/dev/jna/jna/5.18.1/jna-5.18.1.jar", libFolder.getPath()));
                            libUrl = new URL(String.format("%s/downloads/extra-libs/jna-5.18.1.jar", Main.getMorpheusAPI()));
                            isCustomLib = true;
                        }
                        if (isJavaObjcBridge) {
                            // Use ARM64-compatible java-objc-bridge for macOS ARM (1.2.0+ has native ARM64 support)
                            file = new File(String.format("%s/ca/weblite/java-objc-bridge/1.2.0/java-objc-bridge-1.2.0.jar", libFolder.getPath()));
                            libUrl = new URL(String.format("%s/downloads/extra-libs/java-objc-bridge-1.2.0.jar", Main.getMorpheusAPI()));
                            isCustomLib = true;
                        }
                    } else if (isLinuxArm || isLinuxRiscV) {
                        if (isLwjgl33) {
                            if (split.length == 4) {
                                libUrl = new URL(String.format("%s/downloads/extra-libs/lwjgl-3.3.6/%s-%s-%s.jar", Main.getMorpheusAPI(), split[1], split[3], os_arch.toLowerCase().replace("arm64", "aarch64")));
                            } else if (split.length == 3) {
                                libUrl = new URL(String.format("%s/downloads/extra-libs/lwjgl-3.3.6/%s.jar", Main.getMorpheusAPI(), split[1]));
                            }
                            isCustomLib = true;
                        }
                    } else {
                        if (artifact.url != null && !artifact.url.isEmpty()) libUrl = new URL(artifact.url);
                    }

                    /* if the library jar doesn't exist or its hash is invalidated, download from mojang repo */
                    /* for custom libs, always download to ensure latest version */
                    if (libUrl != null && (isCustomLib || !file.exists() || file.exists() && !artifact.sha1.equals(CryptoEngine.fileHash(file, "SHA-1")))) {
                        file.getParentFile().mkdirs();
                        ParallelTasks tasks = new ParallelTasks();
                        tasks.add(new DownloadFileTask(libUrl, file.getPath()));
                        tasks.go();
                    }

                    /* Append the library path to local list if not present */
                    if (!paths.contains(file.toURI().toURL())) {
                        paths.add(file.toURI().toURL());
                        log.info(String.format("Loading: %s", file.toURI().toURL()));
                    }
                }
            }

            /* Reconstructs library path from name and eventually download it, this is used by old json formats and is even used by modloaders */
            String[] namesplit = lib.name.split(":");

            /* Skip LWJGL 3.x on macOS ARM */
            if (isMacArm && lib.name.contains("lwjgl") && namesplit.length >= 3 && namesplit[2].matches("3\\..*")) {
                continue;
            }

            String libpath = String.format("%s/%s/%s/%s-%s.jar", namesplit[0].replace(".", "/"), namesplit[1], namesplit[2], namesplit[1], namesplit[2]);
            File libfile = new File(String.format("%s/%s", libFolder.getPath(), libpath));

            /* when url is provided in json, the specified source will used to download library, instead if not, will used mojang url */
            String liburl = (lib.url != null ? lib.url : Main.getLibrariesURL());
            URL downloadsource = new URL(String.format("%s/%s", liburl, libpath));

            /* check if library isn't present on disk and check if needed library actually is available from download source */
            try {
                int response = ((HttpURLConnection) downloadsource.openConnection()).getResponseCode();
                if (!libfile.exists() && response == 200) {
                    libfile.mkdirs();
                    ParallelTasks tasks = new ParallelTasks();
                    tasks.add(new DownloadFileTask(downloadsource, libfile.getPath()));
                    tasks.go();
                }
            } catch (Exception e) {
            }

            /* Append the library path to local list if not present */
            if (!paths.contains(libfile.toURI().toURL())) {
                paths.add(libfile.toURI().toURL());
                log.info(String.format("Loading: %s", libfile.toURI().toURL()));
            }
        }
        return paths;
    }

    /* this determine which library should be used, some minecraft versions need to use
     * a different library version to work on certain systems, pratically are "Exceptions"
     * WARNING: Potentially bugged and may not follow what mojang json want do */
    private boolean checkRule(ArrayList<MojangProduct.Game.Rule> rules) {
        boolean defaultValue = false;
        for (MojangProduct.Game.Rule rule : rules) {
            if (rule.os == null) {
                defaultValue = rule.action.equals("allow");
            } else if (rule.os != null && OSUtils.getPlatform().name().contains(rule.os.name.replace("osx", "mac"))) {
                defaultValue = rule.action.equals("allow");
            }
        }
        return defaultValue;
    }

    private void setupNatives(MojangProduct.Game game, File nativesFolder) throws MalformedURLException {
        String os_arch = OSUtils.getOSArch();
        boolean isArmProcessor = (os_arch.contains("arm") || os_arch.contains("aarch"));
        boolean isRiscVProcessor = os_arch.contains("riscv64");

        Set<String> downloadedNatives = new HashSet<>();

        BiConsumer<String, String> downloadOnce = (url, reason) -> {
            try {
                if (downloadedNatives.add(url)) {
                    Utils.downloadAndUnzipNatives(new URL(url), nativesFolder, log);
                    log.info(String.format("Downloaded and extracted %s (%s)", url, reason));
                } else {
                    log.debug(String.format("Skipped duplicate native %s", url));
                }
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        };

        for (MojangProduct.Game.Library lib : game.libraries) {
            MojangProduct.Game.Classifiers classifiers = lib.downloads.classifiers;
            if (classifiers != null) {
                MojangProduct.Game.NativesWindows win32 = classifiers.natives_windows_32;
                MojangProduct.Game.NativesWindows win64 = classifiers.natives_windows_64;
                MojangProduct.Game.NativesWindows win = classifiers.natives_windows;
                MojangProduct.Game.NativesLinux linux = classifiers.natives_linux;
                MojangProduct.Game.NativesOsx osx = classifiers.natives_osx;
                MojangProduct.Game.NativesOsx macos = classifiers.natives_macos;

                switch (OSUtils.getPlatform()) {
                    case windows:
                        if (win32 != null) downloadOnce.accept(win32.url, "windows-32");
                        if (win64 != null) downloadOnce.accept(win64.url, "windows-64");
                        if (win != null) downloadOnce.accept(win.url, "windows");
                        break;
                    case linux:
                        if (isArmProcessor || isRiscVProcessor) break;
                        if (linux != null) downloadOnce.accept(linux.url, "linux");
                        break;
                    case macos:
                        if (isArmProcessor && lib.name.contains("lwjgl") && (lib.name.contains("3.1") || lib.name.contains("3.2")) && (osx != null || macos != null)) {
                            log.debug(String.format("Skipping LWJGL 3.1.x/3.2.x natives %s (using 3.3.1 ARM64)", lib.name));
                            break;
                        }
                        if (osx != null) downloadOnce.accept(osx.url, "osx");
                        if (macos != null) downloadOnce.accept(macos.url, "macos");
                        break;
                    default:
                        log.error("Unsupported OS platform");
                }
            }

            /* Mojang with newer versions like 1.16+ introduces new format for natives in json model,
             * Plus this method provides recognition for eventual arm natives */
            if (lib.name.contains("native") && lib.rules != null && checkRule(lib.rules)) {
                boolean isArmNative = lib.name.contains("arm") || lib.name.contains("aarch");
                boolean compatible = true;

                if (isArmNative && !isArmProcessor) compatible = false;
                if (!isArmNative && isArmProcessor) compatible = false;

                if (!compatible) continue;

                downloadOnce.accept(lib.downloads.artifact.url, "mojang-new-native");
            }
        }
        /* Additional code to download missing arm natives */
        if (isArmProcessor || isRiscVProcessor) {
            for (MojangProduct.Game.Library lib : game.libraries) {
                switch (OSUtils.getPlatform()) {
                    case macos:
                        if (!isArmProcessor) break;
                        // LWJGL 2.x
                        if (lib.downloads.classifiers != null && lib.downloads.classifiers.natives_osx != null && lib.downloads.classifiers.natives_osx.url.contains("lwjgl-platform-2")) {
                            downloadOnce.accept(Main.getMorpheusAPI() + "/downloads/extra-natives/lwjgl-2-macos-aarch64.zip", "lwjgl2-macos-arm");
                            break;
                        }
                        // LWJGL 3.3+
                        downloadOnce.accept(Main.getMorpheusAPI() + "/downloads/extra-natives/lwjgl-3.3.1-macos-aarch64.zip", "lwjgl3-linux-arm");
                        break;
                    case linux:
                        if (isArmProcessor) {
                            // LWJGL 2.x
                            if (lib.downloads.classifiers != null && lib.downloads.classifiers.natives_linux != null && lib.downloads.classifiers.natives_linux.url.contains("lwjgl-platform-2")) {
                                downloadOnce.accept(Main.getMorpheusAPI() + "/downloads/extra-natives/lwjgl-2-linux-aarch64.zip", "lwjgl2-linux-arm");
                            }
                            // LWJGL 3.3+
                            if (lib.name.contains("native") && lib.name.contains("lwjgl") && lib.rules != null && checkRule(lib.rules)) {
                                downloadOnce.accept(Main.getMorpheusAPI() + "/downloads/extra-natives/lwjgl-3.3.6-linux-aarch64.zip", "lwjgl3-linux-arm");
                            }
                        } else if (isRiscVProcessor) {
                            // LWJGL 2.x
                            if (lib.downloads.classifiers != null && lib.downloads.classifiers.natives_linux != null && lib.downloads.classifiers.natives_linux.url.contains("lwjgl-platform-2")) {
                                downloadOnce.accept(Main.getMorpheusAPI() + "/downloads/extra-natives/lwjgl-2-linux-riscv64.zip", "lwjgl2-linux-riscv64");
                            }
                            // LWJGL 3.3+
                            if (lib.name.contains("native") && lib.name.contains("lwjgl") && lib.rules != null && checkRule(lib.rules)) {
                                downloadOnce.accept(Main.getMorpheusAPI() + "/downloads/extra-natives/lwjgl-3.3.6-linux-riscv64.zip", "lwjgl3-linux-riscv64");
                            }
                        }
                        break;
                }
            }
        }
    }

    private void setupAssets(MojangProduct.Game game) throws IOException, ParseException, InterruptedException {
        /* Download assets indexes from mojang repo */
        File indexesPath = new File(String.format("%s/indexes/%s.json", env.getAssetsFolder().getPath(), game.assetIndex.id));
        if (!indexesPath.exists()) {
            indexesPath.mkdirs();
            ParallelTasks tasks = new ParallelTasks();
            tasks.add(new DownloadFileTask(new URL(game.assetIndex.url), indexesPath.getPath()));
            tasks.go();
            log.info(indexesPath.getPath() + " was created");
        }

        /* Fetch all the entries and read properties */
        JSONObject json_objects = (JSONObject) ((JSONObject) jsonParser.parse(new FileReader(indexesPath))).get("objects");
        json_objects.keySet().forEach(keyStr -> {
            JSONObject json_entry = (JSONObject) json_objects.get(keyStr);
            String size = json_entry.get("size").toString();
            String hash = json_entry.get("hash").toString();

            /* the asset parent folders is the first two chars of the asset hash
             * "asset" is intended as the single resource file of the game */
            String directory = hash.substring(0, 2);

            try {
                boolean isLegacy = game.assetIndex.id.contains("pre-1.6");

                /* legacy versions use .minecraft/resources instead of .minecraft/assets */
                File objectsPath;
                if (isLegacy)
                    objectsPath = new File(String.format("%s/resources/%s", env.getGameFolder().getPath(), keyStr));
                else
                    objectsPath = new File(String.format("%s/objects/%s/%s", env.getAssetsFolder().getPath(), directory, hash));

                /* if asset doesn't exist or its hash is invalid, re-download from mojang */
                if (!objectsPath.exists() || objectsPath.exists() && !hash.equals(CryptoEngine.fileHash(objectsPath, "SHA-1"))) {
                    objectsPath.mkdirs();
                    URL object_url = new URL(String.format("%s/%s/%s", Main.getAssetsURL(), directory, hash));

                    ParallelTasks tasks = new ParallelTasks();
                    tasks.add(new DownloadFileTask(object_url, objectsPath.getPath()));
                    tasks.go();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void initDiscordRPC(String status) throws Exception {
        String os_arch = OSUtils.getOSArch();
        try {
            Discord discord = new Discord(1061674345405100082L);
            discord.setActivity("In Partita", String.format("Playing: %s", status));
        } catch (Exception e) {
            log.error("An error occurred with discord rich presence!");
            e.printStackTrace();
        }
    }

    private static String buildRPCstatus(String mcVersion, String gameVersion) {
        String gameType = "Vanilla";
        if (mcVersion.contains("forge")) {
            gameType = "Forge";
        } else if (mcVersion.contains("fabric")) {
            gameType = "Fabric";
        } else if (mcVersion.contains("optifine")) {
            gameType = "OptiFine";
        } else if (mcVersion.contains("quilt")) {
            gameType = "Quilt";
        }
        return String.format("%s %s", gameType, gameVersion);
    }

    private List<URL> dedupeLibraries(List<URL> paths) {
        Map<String, LibEntry> best = new HashMap<>();
        // pattern: …/libraries/group/path/artifact/version/artifact-version.jar
        Pattern p = Pattern.compile(".*/libraries/(.+)/(.+)/(\\d+(?:[\\.\\-\\w]*)?)/\\2-\\3\\.jar$");

        for (URL url : paths) {
            String path = url.getPath().replace('\\', '/');
            Matcher m = p.matcher(path);
            if (m.matches()) {
                String groupPath = m.group(1); // es. "org/ow2/asm/asm"
                String artifact = m.group(2); // es. "asm"
                String version = m.group(3); // es. "9.8"
                String groupId = groupPath.replace('/', '.'); // "org.ow2.asm.asm"
                String key = groupId + ":" + artifact;

                LibEntry current = best.get(key);
                if (current == null || compareVersions(version, current.version) > 0) {
                    best.put(key, new LibEntry(version, url));
                }
            } else {
                // JAR “non standard” sul path: usiamo l’URL completo come chiave
                String key = url.toString();
                if (!best.containsKey(key)) {
                    best.put(key, new LibEntry("", url));
                }
            }
        }

        // raccogliamo gli URL vincenti
        List<URL> result = new ArrayList<URL>();
        for (LibEntry e : best.values()) {
            result.add(e.url);
        }
        return result;
    }

    private int compareVersions(String v1, String v2) {
        String[] a1 = v1.split("[\\.\\-]");
        String[] a2 = v2.split("[\\.\\-]");
        int n = Math.max(a1.length, a2.length);
        for (int i = 0; i < n; i++) {
            int x = i < a1.length ? parseIntOrZero(a1[i]) : 0;
            int y = i < a2.length ? parseIntOrZero(a2[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    private int parseIntOrZero(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static class LibEntry {
        private final String version;
        private final URL url;

        public LibEntry(String version, URL url) {
            this.version = version;
            this.url = url;
        }

        public String getVersion() {
            return version;
        }

        public URL getUrl() {
            return url;
        }
    }
}
