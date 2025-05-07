package team.morpheus.launcher;

import club.minnced.discord.rpc.DiscordEventHandlers;
import club.minnced.discord.rpc.DiscordRPC;
import club.minnced.discord.rpc.DiscordRichPresence;
import com.google.gson.Gson;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import team.morpheus.launcher.logging.MyLogger;
import team.morpheus.launcher.model.LauncherVariables;
import team.morpheus.launcher.model.products.MojangProduct;
import team.morpheus.launcher.utils.*;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.file.Files;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Launcher {

    private static final MyLogger log = new MyLogger(Launcher.class);
    private JSONParser jsonParser = new JSONParser();
    private File gameFolder, assetsFolder;

    private MojangProduct vanilla;
    private MojangProduct.Version target;

    private MojangProduct.Game game; // Vanilla / Optifine / Fabric / Forge
    private MojangProduct.Game inherited; // just Vanilla (is parent of modloader)

    public Launcher(LauncherVariables variables) throws Exception {
        boolean isLatestVersion = false;
        try {
            /* Get all versions from mojang */
            vanilla = retrieveVersions();

            /* Find version by name gave by user */
            String targetName = variables.getMcVersion();

            if (variables.getMcVersion().equalsIgnoreCase("latest")) {
                targetName = vanilla.latest.release;
                isLatestVersion = true;
            }
            if (variables.getMcVersion().equalsIgnoreCase("snapshot")) {
                targetName = vanilla.latest.snapshot;
                isLatestVersion = true;
            }

            target = findVersion(vanilla, targetName);
        } catch (Exception e) {
            log.error("Cannot download/parse mojang versions json");
        }

        // Make .minecraft/
        gameFolder = makeDirectory(variables.getGamePath());

        // Make .minecraft/assets/
        assetsFolder = makeDirectory(String.format("%s/assets", gameFolder.getPath()));

        // Make .minecraft/versions/<gameVersion>
        File versionPath = makeDirectory(String.format("%s/versions/%s", gameFolder.getPath(), variables.getMcVersion()));

        // Download json to .minecraft/versions/<gameVersion>/<gameVersion.json
        File jsonFile = new File(String.format("%s/%s.json", versionPath.getPath(), variables.getMcVersion()));
        if (target != null && target.url != null) {
            /* Extract json file hash from download url */
            String jsonHash = target.url.substring(target.url.lastIndexOf("/") - 40, target.url.lastIndexOf("/"));

            /* if the json doesn't exist or its hash is invalidated, download from mojang repo */
            /* isLatestVersion is put to skip sha check when "latest" or "snapshot" is used */
            if (!jsonFile.exists() || jsonFile.exists() && !jsonHash.equals(CryptoEngine.fileHash(jsonFile, "SHA-1")) || isLatestVersion) {
                ParallelTasks tasks = new ParallelTasks();
                tasks.add(new DownloadFileTask(new URL(target.url), jsonFile.getPath()));
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
        game = retrieveGame(jsonFile);

        /* Download vanilla jar to .minecraft/versions/<gameVersion>/<gameVersion.jar */
        File jarFile = new File(String.format("%s/%s.jar", versionPath.getPath(), variables.getMcVersion()));
        if (game.downloads != null && game.downloads.client != null) {
            String jarHash = game.downloads.client.sha1;

            /* if the vanilla jar doesn't exist or its hash is invalidated, download from mojang repo */
            if (!jarFile.exists() || jarFile.exists() && !jarHash.equals(CryptoEngine.fileHash(jarFile, "SHA-1"))) {
                ParallelTasks tasks = new ParallelTasks();
                tasks.add(new DownloadFileTask(new URL(game.downloads.client.url), jarFile.getPath()));
                tasks.go();
            }
        }

        // Make natives dir .minecraft/versions/<gameVersion>/natives/
        File nativesPath = makeDirectory(String.format("%s/natives", versionPath.getPath()));

        /* If internet is available download the parent (vanilla) version when you launch a modloader
         * Example: downloads the "1.19.2" while you launch "fabric-loader-0.14.21-1.19.2"
         * Because inside optifine, fabric or forge json there is a field called "inheritsFrom"
         * "inheritsFrom" basically describes on which vanilla version the modloader bases of */
        if (game.inheritsFrom != null) {
            if (vanilla != null) target = findVersion(vanilla, game.inheritsFrom);

            File inheritedVersionPath = makeDirectory(String.format("%s/versions/%s", gameFolder.getPath(), game.inheritsFrom));

            /* Download the vanilla json which modloader put its basis on */
            File inheritedjsonFile = new File(String.format("%s/%s.json", inheritedVersionPath.getPath(), game.inheritsFrom));
            if (target != null && target.url != null) {
                String jsonHash = target.url.substring(target.url.lastIndexOf("/") - 40, target.url.lastIndexOf("/"));

                /* if the vanilla json doesn't exist or its hash is invalidated, download from mojang repo */
                if (!inheritedjsonFile.exists() || inheritedjsonFile.exists() && !jsonHash.equals(CryptoEngine.fileHash(inheritedjsonFile, "SHA-1"))) {
                    ParallelTasks tasks = new ParallelTasks();
                    tasks.add(new DownloadFileTask(new URL(target.url), inheritedjsonFile.getPath()));
                    tasks.go();
                }
            }

            inherited = retrieveGame(inheritedjsonFile);

            /* Download the vanilla client jar when you launch a modloader that put its basis on it */
            File inheritedjarFile = new File(String.format("%s/%s.jar", inheritedVersionPath.getPath(), game.inheritsFrom));
            if (inherited.downloads != null && inherited.downloads.client != null) {
                String jarHash = inherited.downloads.client.sha1;

                if (!inheritedjarFile.exists() || inheritedjarFile.exists() && !jarHash.equals(CryptoEngine.fileHash(inheritedjarFile, "SHA-1"))) {
                    ParallelTasks tasks = new ParallelTasks();
                    tasks.add(new DownloadFileTask(new URL(inherited.downloads.client.url), inheritedjarFile.getPath()));
                    tasks.go();
                }
            }
        }

        /* This variable returns ALWAYS the vanilla version, even when you launch modloader */
        MojangProduct.Game vanilla = (inherited != null ? inherited : game);

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
                    .replace("${version_name}", game.id) // Version launched
                    .replace("${game_directory}", gameFolder.getPath()) // Game root dir
                    .replace("${game_assets}", assetsFolder.getPath()) // Game assets root dir
                    .replace("${assets_root}", assetsFolder.getPath()) // Same as the previous one
                    .replace("${assets_index_name}", vanilla.assetIndex.id) // assets index json filename
                    .replace("${auth_uuid}", Main.getMojangSession().getUUID()) // player uuid
                    .replace("${auth_access_token}", Main.getMojangSession().getSessionToken()) // player token for premium
                    .replace("${user_type}", "msa").replace("${version_type}", game.type) // type of premium auth
                    .replace("${user_properties}", "{}"); // unknown
            gameargs.add(s);
        }
        /* Append modloader launching arguments to vanilla */
        if (inherited != null) {
            for (String s : argbuilder(game)) {
                if (!gameargs.contains(s)) {
                    gameargs.add(s);
                }
            }
        }

        /* Put the client jar to url list */
        if (Main.getVanilla() != null) {
            if (variables.isModded()) {
                paths.addAll(setupLibraries(game));  /* Append modloader libraries if the game is modded */
                paths.addAll(setupLibraries(vanilla)); /* Append vanilla libraries */

                paths = dedupeLibraries(paths);

                /* Due to unknown modloader reasons, we need to load even the inherited (vanilla) version */
                jarFile = new File(String.format("%s/%s.jar", (new File(String.format("%s/versions/%s", gameFolder.getPath(), vanilla.id))).getPath(), vanilla.id));

                /* Set the java.class.path to make modloaders like forge/fabric to work */
                makeModloaderCompatibility(paths, jarFile);
            } else {
                paths.addAll(setupLibraries(vanilla)); /* Append vanilla libraries */
            }

            if (paths.add(jarFile.toURI().toURL())) log.info(String.format("loading: %s", jarFile.toURI().toURL()));

            initDiscordRPC(buildRPCstatus(mcLowercase, vanilla.id));
        }

        /* Due compatibility issues some modloaders should run through -cp instead of using dynamic classloading */
        if (variables.isClassPath()) {
            /* Build classpath */
            StringBuilder classPath = new StringBuilder();
            for (URL path : paths)
                classPath.append(new File(path.toURI()).getPath()).append(OSUtils.getPlatform().equals(OSUtils.OS.windows) ? ";" : ":");
            classPath.append(new File(jarFile.toURI()).getPath());

            String librarypath = String.format("-Djava.library.path=%s", System.getProperty("java.library.path"));
            String jnapath = String.format("-Djna.tmpdir=%s", System.getProperty("java.library.path"));
            String lwjglpath = String.format("-Dorg.lwjgl.system.SharedLibraryExtractPath=%s", System.getProperty("java.library.path"));
            String nettypath = String.format("-Dio.netty.native.workdir=%s", System.getProperty("java.library.path"));

            List<String> command = new ArrayList<>();
            command.add("java");
            if (variables.isStartOnFirstThread()) {
                command.add("-XstartOnFirstThread");
            }
            command.add(librarypath);
            command.add(jnapath);
            command.add(lwjglpath);
            command.add(nettypath);
            command.add("-cp");
            command.add(classPath.toString());
            command.add(game.mainClass);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.command().addAll(gameargs);

            /* Start the children process (game) */
            log.info("Using classpath instead of classloader");
            Process process = processBuilder.start();

            /* Forward the output from children process into parent process */
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) System.out.println(line);
        } else {
            /* Launch through classloader */
            doClassloading(paths, gameargs, game.mainClass);
        }
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

    private void doFabricSetup(String mcVersion, File jsonFile) throws MalformedURLException, InterruptedException {
        String[] split = mcVersion.split("-");
        ParallelTasks tasks = new ParallelTasks();
        tasks.add(new DownloadFileTask(new URL(String.format("%s/loader/%s/%s/profile/json", Main.getFabricVersionsURL(), split[3], split[2])), jsonFile.getPath()));
        tasks.go();
    }

    private void doOptiForgeSetup(String mcVersion, File jsonFile) throws IOException, ParseException, InterruptedException {
        /* Install optifine */
        doOptifineSetup(mcVersion, jsonFile);

        /* Make the modlist json */
        String modlistName = String.format("tempModList-%s.json", mcVersion);
        File modlistFile = new File(String.format("%s/%s", gameFolder, modlistName));
        JSONObject optiJsonObject = new JSONObject();
        optiJsonObject.put("repositoryRoot", String.format("%s/libraries", gameFolder));
        optiJsonObject.put("modRef", getOptifineModList(jsonFile));  // Qui usiamo l'array modRefArray che abbiamo popolato sopra
        FileWriter file = new FileWriter(modlistFile);
        file.write(optiJsonObject.toJSONString());
        file.flush();
        file.close();

        /* Install forge */
        doForgeSetup(mcVersion, jsonFile);

        /* Do json patches */
        FileReader reader = new FileReader(jsonFile);
        JSONObject jsonObject = (JSONObject) jsonParser.parse(reader);
        jsonObject.replace("minecraftArguments", jsonObject.get("minecraftArguments"), String.format("%s --modListFile %s", jsonObject.get("minecraftArguments"), modlistName));
        jsonObject.replace("id", jsonObject.get("id"), mcVersion);
        FileWriter writer = new FileWriter(jsonFile);
        writer.write(jsonObject.toJSONString());
        writer.flush();
        writer.close();
    }

    /* Extract optifine library name from optifine json */
    private JSONArray getOptifineModList(File jsonFile) throws IOException, ParseException {
        FileReader OptifineReader = new FileReader(jsonFile);
        JSONObject OptifineJsonObject = (JSONObject) jsonParser.parse(OptifineReader);
        JSONArray libraries = (JSONArray) OptifineJsonObject.get("libraries");
        JSONArray modRefArray = new JSONArray();
        for (Object libObject : libraries) {
            JSONObject library = (JSONObject) libObject;
            String libraryName = (String) library.get("name");
            if (libraryName != null && libraryName.startsWith("optifine:")) {
                modRefArray.add(libraryName);
            }
        }
        return modRefArray;
    }

    private void doForgeSetup(String mcLowercase, File jsonFile) throws IOException, ParseException, InterruptedException {
        /* Fetch forge versions */
        String forgeVersionList = Utils.makeGetRequest(new URL(Main.getForgeVersionsURL()));
        log.info("Fetching available forge versions");

        /* Setup json parsing */
        JSONParser jsonParser = new JSONParser();
        JSONObject jsonObject = (JSONObject) jsonParser.parse(forgeVersionList);

        List<String> candidate = new ArrayList<>();

        for (Object key : jsonObject.keySet()) {
            String versionKey = (String) key;
            JSONArray versionList = (JSONArray) jsonObject.get(versionKey);
            /* Filter by game version */
            if (mcLowercase.split("-")[0].contains(versionKey)) {
                for (Object forges : versionList) {
                    String fullVersion = (String) forges;
                    String forgeVersion = fullVersion.split("-")[1];

                    String[] split = forgeVersion.split("\\.");

                    /* Append to list possible downloadable forge versions */
                    if (mcLowercase.contains(forgeVersion) || mcLowercase.contains(String.format("%s.%s.%s", split[0], split[1], split[2]))) {
                        candidate.add(fullVersion);
                    }
                }
            }
        }
        /* Pick the last entry */
        String forgeInstallerVersion = candidate.get(candidate.size() - 1);
        URL forgeInstallerUrl = new URL(String.format("%s%s/forge-%s-installer.jar", Main.getForgeInstallerURL(), forgeInstallerVersion, forgeInstallerVersion));
        File forgeInstallerFile = new File(String.format("%s/forge-%s-installer.jar", System.getProperty("java.io.tmpdir"), forgeInstallerVersion));

        /* Download latest forge for the selected minecraft version*/
        if (!forgeInstallerFile.exists()) {
            ParallelTasks tasks = new ParallelTasks();
            tasks.add(new DownloadFileTask(forgeInstallerUrl, forgeInstallerFile.getPath()));
            tasks.go();
        }
        doForgeUnpack(forgeInstallerFile, jsonFile, forgeInstallerVersion);
    }

    /* This function handles the unpacking of the forge installer */
    private void doForgeUnpack(File forgeInstallerFile, File jsonFile, String forgeLibName) throws ParseException, IOException {
        String[] forgeVersion = forgeLibName.split("-");
        StringBuilder installProfileContent = new StringBuilder();

        /* This will contain the jar archive name to be extracted */
        String forgeFilePath = "";
        /* This will contain the path to jar will extracted */
        String forgeTargetPath = "";

        /* Search and read install_profile.json from installer jar */
        ZipFile zipFile = new ZipFile(forgeInstallerFile);
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();

            /* Search the installation json file that describes how should forge will be installed */
            if (entry.getName().equals("install_profile.json")) {
                InputStream inputStream = zipFile.getInputStream(entry);

                /* Read json content */
                InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                BufferedReader reader = new BufferedReader(inputStreamReader);
                String line;
                while ((line = reader.readLine()) != null) {
                    installProfileContent.append(line).append("\n");
                }

                /* Parse install_profile.json content */
                JSONParser parser = new JSONParser();
                Object obj = parser.parse(installProfileContent.toString());
                JSONObject jsonObject = (JSONObject) obj;
                /* All versionInfo values will be saved as dedicated json in versions folder */
                JSONObject versionInfo = (JSONObject) jsonObject.get("versionInfo");
                /* This array will contain the installation details */
                JSONObject installInfo = (JSONObject) jsonObject.get("install");

                /* starting from (1.12.2) */
                if (jsonObject.get("path") != null) {
                    forgeTargetPath = jsonObject.get("path").toString();
                }

                /* Extract the installation details (1.6.4 -> 1.11.2) */
                if (installInfo != null) {
                    forgeFilePath = installInfo.get("filePath").toString();
                    forgeTargetPath = installInfo.get("path").toString();
                }

                /* This is required by launcher to recognize that is a modded version (1.6.4 -> 1.11.2) */
                if (versionInfo != null) {
                    versionInfo.put("inheritsFrom", forgeVersion[0]);

                    /* Save forge custom json into its version folder */
                    try (FileWriter file = new FileWriter(jsonFile.getPath())) {
                        file.write(versionInfo.toJSONString());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } else if (entry.getName().equals("version.json")) {
                try (InputStream inputStream = zipFile.getInputStream(entry); OutputStream outputStream = new FileOutputStream(jsonFile.getPath())) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, length);
                    }
                }
            }
        }
        /* Reinitialize another jar content scan */
        entries = zipFile.entries();
        /* Pick and extract the jar into libraries folder */
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().contains(".jar")) {
                File libFolder = makeDirectory(String.format("%s/libraries", gameFolder.getPath()));

                String[] namesplit = forgeTargetPath.split(":");
                File libpath = makeDirectory(String.format("%s/%s/%s/%s/", libFolder.getPath(), namesplit[0].replace(".", "/"), namesplit[1], namesplit[2]));
                File libfile = new File(String.format("%s/%s-%s.jar", libpath.getPath(), namesplit[1], namesplit[2]));

                if (libfile.createNewFile())
                    try (InputStream inputStream = zipFile.getInputStream(entry); OutputStream outputStream = new FileOutputStream(libfile)) {
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = inputStream.read(buffer)) > 0) {
                            outputStream.write(buffer, 0, length);
                        }
                    }
            }
        }
    }

    private void doOptifineSetup(String mcVersion, File jsonFile) throws IOException, ParseException, InterruptedException {
        /* Fetch optifine versions */
        String ofVersionList = Utils.makeGetRequest(new URL(Main.getOptifineVersionsURL()));

        /* Setup json parsing */
        JSONParser jsonParser = new JSONParser();
        JSONObject jsonObject = (JSONObject) jsonParser.parse(ofVersionList);

        /* Iterate optifine game versions */
        for (Object key : jsonObject.keySet()) {
            String versionKey = (String) key;
            /* Find the correct game version for optifine */
            if (mcVersion.split("-")[0].equals(versionKey)) {
                JSONObject versionList = (JSONObject) jsonObject.get(versionKey);

                /* Prepare optifine installer url */
                URL ofInstallerURL = new URL(String.format("%s/downloads/extra-optifine/%s.jar", Main.getMorpheusAPI(), versionList.get("name")));
                File ofInstallerFile = new File(String.format("%s/%s.jar", System.getProperty("java.io.tmpdir"), versionList.get("name")));

                /* Download optifine installer into temp folder */
                if (!ofInstallerFile.exists()) {
                    ParallelTasks tasks = new ParallelTasks();
                    tasks.add(new DownloadFileTask(ofInstallerURL, ofInstallerFile.getPath()));
                    tasks.go();
                }

                ZipFile ofInstallerZip = new ZipFile(ofInstallerFile);

                /* Some code for optifine folders and file names */
                String ofVer = OFUtils.getOptiFineVersion(ofInstallerZip);
                String[] ofVers = OFUtils.tokenize(ofVer, "_");
                String mcVer = ofVers[1];
                String ofEd = OFUtils.getOptiFineEdition(ofVers);

                /* Make optifine library folder */
                File ofLibraryPath = new File(String.format("%s/libraries/optifine/OptiFine/%s_%s", gameFolder, mcVer, ofEd));
                if (!ofLibraryPath.exists()) ofLibraryPath.mkdirs();

                /* Copy optifine library to libraries folder */
                File ofLibraryFile = new File(String.format("%s/OptiFine-%s_%s.jar", ofLibraryPath, mcVer, ofEd));
                OFUtils.copyFile(ofInstallerFile, ofLibraryFile);

                /* Retrieve launchwrapper version from txt */
                String launchwrapperVersion = OFUtils.getLaunchwrapperVersion(ofInstallerZip.getInputStream(ofInstallerZip.getEntry("launchwrapper-of.txt")));

                /* Build launchwrapper target folder */
                File launchwrapperPath = new File(String.format("%s/libraries/optifine/launchwrapper-of/%s", gameFolder, launchwrapperVersion));
                if (!launchwrapperPath.exists()) launchwrapperPath.mkdirs();
                String launchwrapperFileName = String.format("launchwrapper-of-%s.jar", launchwrapperVersion);
                File launchwrapperFile = new File(String.format("%s/%s", launchwrapperPath, launchwrapperFileName));

                InputStream fin = ofInstallerZip.getInputStream(ofInstallerZip.getEntry(launchwrapperFileName));
                if (fin != null) {
                    FileOutputStream fout = new FileOutputStream(launchwrapperFile);
                    OFUtils.copyAll(fin, fout);
                    fout.flush();
                    fin.close();
                    fout.close();
                }

                /* Download the vanilla json if absent */
                MojangProduct.Version ver = findVersion(vanilla, mcVer);
                File vanillaJsonPath = new File(String.format("%s/versions/%s", gameFolder, ver.id));
                if (!vanillaJsonPath.exists()) vanillaJsonPath.mkdirs();

                File vanillaJsonFile = new File(String.format("%s/%s.json", vanillaJsonPath, ver.id));
                if (!vanillaJsonFile.exists()) {
                    ParallelTasks tasks = new ParallelTasks();
                    tasks.add(new DownloadFileTask(new URL(ver.url), vanillaJsonFile.getPath()));
                    tasks.go();
                }

                JSONParser jp = new JSONParser();
                JSONObject root = (JSONObject) jp.parse(new FileReader(vanillaJsonFile));
                JSONObject rootNew = new JSONObject();
                rootNew.put("id", mcVersion);
                rootNew.put("inheritsFrom", mcVer);
                rootNew.put("type", "release");
                JSONArray libs = new JSONArray();
                rootNew.put("libraries", libs);
                String mainClass = (String) root.get("mainClass");
                if (!mainClass.startsWith("net.minecraft.launchwrapper.")) {
                    mainClass = "net.minecraft.launchwrapper.Launch";
                    rootNew.put("mainClass", mainClass);
                    String mcArgs = (String) root.get("minecraftArguments");
                    JSONObject libLw;
                    if (mcArgs != null) {
                        mcArgs = mcArgs + "  --tweakClass optifine.OptiFineTweaker";
                        rootNew.put("minecraftArguments", mcArgs);
                    } else {
                        libLw = new JSONObject();
                        JSONArray argsGame = new JSONArray();
                        argsGame.add("--tweakClass");
                        argsGame.add("optifine.OptiFineTweaker");
                        libLw.put("game", argsGame);
                        rootNew.put("arguments", libLw);
                    }
                    libLw = new JSONObject();
                    libLw.put("name", "optifine:launchwrapper-of:" + launchwrapperVersion);
                    libs.add(0, libLw);
                }
                JSONObject libOf = new JSONObject();
                libOf.put("name", "optifine:OptiFine:" + mcVer + "_" + ofEd);
                libs.add(0, libOf);

                FileWriter writer = new FileWriter(jsonFile);
                writer.write(rootNew.toJSONString());
                writer.flush();
                writer.close();
            }
        }
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

    /* Retrieve versions list from mojang server */
    private MojangProduct retrieveVersions() throws IOException {
        return new Gson().fromJson(Utils.makeGetRequest(new URL(Main.getVersionsURL())), MojangProduct.class);
    }

    /* Retrieve the version json */
    private MojangProduct.Game retrieveGame(File file) throws IOException {
        return new Gson().fromJson(new String(Files.readAllBytes(file.toPath())), MojangProduct.Game.class);
    }

    /* Search a version by "name" from the version list */
    private MojangProduct.Version findVersion(MojangProduct data, String name) {
        for (Object version : data.versions.stream().filter(f -> f.id.equalsIgnoreCase(name)).toArray()) {
            return (MojangProduct.Version) version;
        }
        return null;
    }

    private void doClassloading(List<URL> jars, List<String> gameargs, String mainClass) throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        /* Add all url paths to classloader */
        URLClassLoader ucl = new URLClassLoader(jars.toArray(new URL[jars.size()]));
        Thread.currentThread().setContextClassLoader(ucl); // idk but maybe can helpful
        Class<?> c = ucl.loadClass(mainClass);

        /* Mangle game arguments */
        String[] args = new String[]{};
        String[] concat = Utils.concat(gameargs.toArray(new String[gameargs.size()]), args);
        String[] startArgs = Arrays.copyOfRange(concat, 0, concat.length);

        /* Method Handle instead of reflection, to make compatible with jre higher than 8 */
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType mainMethodType = MethodType.methodType(void.class, String[].class);
        MethodHandle mainMethodHandle = lookup.findStatic(c, "main", mainMethodType);

        /* Invoke the main with the given arguments */
        try {
            log.debug(String.format("Invoking: %s", c.getName()));
            mainMethodHandle.invokeExact(startArgs);
        } catch (Throwable e) {
            if (e.getMessage() != null) log.error(e.getMessage());
            e.printStackTrace();
        }
    }

    /* in newer minecraft versions mojang changed how launch arguments are specified in JSON
     * This automatically choose how arguments should be managed */
    private String[] argbuilder(MojangProduct.Game game) {
        if (game.minecraftArguments != null) {
            // New
            return game.minecraftArguments.split(" ");
        } else {
            // Old
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
        List<URL> paths = new ArrayList<>();
        for (MojangProduct.Game.Library lib : game.libraries) {
            File libFolder = new File(String.format("%s/libraries", gameFolder.getPath()));
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

                    /* if the library jar doesn't exist or its hash is invalidated, download from mojang repo */
                    if (artifact.url != null && !artifact.url.isEmpty() && (!file.exists() || file.exists() && !artifact.sha1.equals(CryptoEngine.fileHash(file, "SHA-1")))) {
                        file.mkdirs();
                        ParallelTasks tasks = new ParallelTasks();
                        tasks.add(new DownloadFileTask(new URL(artifact.url), file.getPath()));
                        tasks.go();
                    }

                    /* Append the library path to local list if not present */
                    if (!paths.contains(file.toURI().toURL()) && paths.add(file.toURI().toURL())) {
                        log.info(String.format("Loading: %s", file.toURI().toURL()));
                    }
                }
            }

            /* Reconstructs library path from name and eventually download it, this is used by old json formats and is even used by modloaders */
            String[] namesplit = lib.name.split(":");
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
            if (!paths.contains(libfile.toURI().toURL()) && paths.add(libfile.toURI().toURL())) {
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
        /* Find out what cpu architecture is the user machine, assuming they use baremetal os installation */
        String os_arch = OSUtils.getOSArch();
        boolean isArmProcessor = (os_arch.contains("arm") || os_arch.contains("aarch"));
        boolean isRiscVProcessor = os_arch.contains("riscv64");

        for (MojangProduct.Game.Library lib : game.libraries) {
            MojangProduct.Game.Classifiers classifiers = lib.downloads.classifiers;
            if (classifiers != null) {
                /* Natives pojo model for windows */
                MojangProduct.Game.NativesWindows windows32 = classifiers.natives_windows_32, windows64 = classifiers.natives_windows_64, windows = classifiers.natives_windows;
                /* for gnu/linux */
                MojangProduct.Game.NativesLinux linux = classifiers.natives_linux;
                /* for osx/macos or whatever you want call it */
                MojangProduct.Game.NativesOsx osx = classifiers.natives_osx, macos = classifiers.natives_macos;

                switch (OSUtils.getPlatform()) {
                    /* These seems "duplicated" but is needed for maintaing compatibility with old versions like 1.8.x */
                    case windows:
                        if (windows32 != null) {
                            Utils.downloadAndUnzipNatives(new URL(windows32.url), nativesFolder, log);
                            log.info(String.format("Downloaded and extracted %s for %s", windows32.url, OSUtils.getPlatform()));
                        }
                        if (windows64 != null) {
                            Utils.downloadAndUnzipNatives(new URL(windows64.url), nativesFolder, log);
                            log.info(String.format("Downloaded and extracted %s for %s", windows64.url, OSUtils.getPlatform()));
                        }
                        if (windows != null) {
                            Utils.downloadAndUnzipNatives(new URL(windows.url), nativesFolder, log);
                            log.info(String.format("Downloaded and extracted %s for %s", windows.url, OSUtils.getPlatform()));
                        }
                        break;
                    case linux:
                        if (linux != null) {
                            Utils.downloadAndUnzipNatives(new URL(linux.url), nativesFolder, log);
                            log.info(String.format("Downloaded and extracted %s for %s", linux.url, OSUtils.getPlatform()));
                        }
                        break;
                    /* Dear mojang why you use different natives names in your json?? */
                    case macos:
                        if (osx != null) {
                            Utils.downloadAndUnzipNatives(new URL(osx.url), nativesFolder, log);
                            log.info(String.format("Downloaded and extracted %s for %s", osx.url, OSUtils.getPlatform()));
                        }
                        if (macos != null) {
                            Utils.downloadAndUnzipNatives(new URL(macos.url), nativesFolder, log);
                            log.info(String.format("Downloaded and extracted %s for %s", macos.url, OSUtils.getPlatform()));
                        }
                        break;
                    /* Fallback error in case user have weird os like solaris or bsd */
                    default:
                        log.error("Oops.. seem that your os isn't supported, ask help on https://discord.gg/aerXnBe");
                }
            }
            /* Mojang with newer versions like 1.16+ introduces new format for natives in json model,
             * Plus this method provides recognition for eventual arm natives */
            if (lib.name.contains("native") && lib.rules != null) {
                String nativeName = lib.name;
                String nativeURL = lib.downloads.artifact.url;

                /* Find out which natives allow on user os */
                if (checkRule(lib.rules)) {
                    boolean isArmNative = (lib.name.contains("arm") || lib.name.contains("aarch"));
                    boolean compatible = true;

                    if (isArmNative && !isArmProcessor) compatible = false; // natives ARM on x86 (cpu)
                    if (!isArmNative && isArmProcessor) compatible = false; // natives x86 on ARM (cpu)

                    if (!compatible) continue;
                    Utils.downloadAndUnzipNatives(new URL(nativeURL), nativesFolder, log);
                    log.info(String.format("Downloaded and extracted %s for %s (%s)", nativeURL, OSUtils.getPlatform(), os_arch));
                }
            }
        }
        /* Additional code to download missing arm natives */
        if (isArmProcessor || isRiscVProcessor) for (MojangProduct.Game.Library lib : game.libraries) {
            switch (OSUtils.getPlatform()) {
                case macos:
                    if (!isArmProcessor) break; // if isn't apple silicon mac skip

                    // LWJGL 2.X (up to 1.12.2)
                    if (lib.downloads.classifiers != null && lib.downloads.classifiers.natives_osx != null && lib.downloads.classifiers.natives_osx.url.contains("lwjgl-platform-2")) {
                        String zipUrl = String.format("%s/downloads/extra-natives/lwjgl-2-macos-aarch64.zip", Main.getMorpheusAPI());
                        Utils.downloadAndUnzipNatives(new URL(zipUrl), nativesFolder, log);
                        log.info(String.format("Downloaded and extracted %s for %s", zipUrl, OSUtils.getPlatform()));
                    }
                    break;
                case linux:
                    if (isArmProcessor) {
                        // LWJGL 2.X (up to 1.12.2)
                        if (lib.downloads.classifiers != null && lib.downloads.classifiers.natives_linux != null && lib.downloads.classifiers.natives_linux.url.contains("lwjgl-platform-2")) {
                            String zipUrl = String.format("%s/downloads/extra-natives/lwjgl-2-linux-aarch64.zip", Main.getMorpheusAPI());
                            Utils.downloadAndUnzipNatives(new URL(zipUrl), nativesFolder, log);
                            log.info(String.format("Downloaded and extracted %s for %s", zipUrl, OSUtils.getPlatform()));
                        }
                        // LWJGL 3.3 (1.19+)
                        if (lib.name.contains("native") && lib.rules != null && checkRule(lib.rules) && lib.name.contains("lwjgl")) {
                            String zipUrl = String.format("%s/downloads/extra-natives/lwjgl-3.3-linux-aarch64.zip", Main.getMorpheusAPI());
                            Utils.downloadAndUnzipNatives(new URL(zipUrl), nativesFolder, log);
                            log.info(String.format("Downloaded and extracted %s for %s", zipUrl, OSUtils.getPlatform()));
                        }
                    } else if (isRiscVProcessor) {
                        // LWJGL 2.X (up to 1.12.2)
                        if (lib.downloads.classifiers != null && lib.downloads.classifiers.natives_linux != null && lib.downloads.classifiers.natives_linux.url.contains("lwjgl-platform-2")) {
                            String zipUrl = String.format("%s/downloads/extra-natives/lwjgl-2-linux-riscv64.zip", Main.getMorpheusAPI());
                            Utils.downloadAndUnzipNatives(new URL(zipUrl), nativesFolder, log);
                            log.info(String.format("Downloaded and extracted %s for %s", zipUrl, OSUtils.getPlatform()));
                        }
                    }
                    break;
            }
        }
    }

    private void setupAssets(MojangProduct.Game game) throws IOException, ParseException, InterruptedException {
        /* Download assets indexes from mojang repo */
        File indexesPath = new File(String.format("%s/indexes/%s.json", assetsFolder.getPath(), game.assetIndex.id));
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
                if (isLegacy) objectsPath = new File(String.format("%s/resources/%s", gameFolder.getPath(), keyStr));
                else objectsPath = new File(String.format("%s/objects/%s/%s", assetsFolder.getPath(), directory, hash));

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

    private File makeDirectory(String path) {
        File temp = new File(path);
        if (!temp.exists() && temp.mkdirs()) {
            log.info(String.format("Directory created: %s", temp.getPath()));
        }
        return temp;
    }

    private static void initDiscordRPC(String status) throws Exception {
        String os_arch = OSUtils.getOSArch();
        if (!(os_arch.contains("x86") || os_arch.contains("amd64"))) return;

        DiscordRPC lib = DiscordRPC.INSTANCE;
        DiscordEventHandlers handlers = new DiscordEventHandlers();
        lib.Discord_Initialize("1061674345405100082", handlers, true, "");
        DiscordRichPresence presence = new DiscordRichPresence();

        presence.startTimestamp = System.currentTimeMillis() / 1000;
        presence.details = String.format("Playing: %s", status);
        presence.largeImageKey = "morpheus";
        presence.largeImageText = "";
        lib.Discord_UpdatePresence(presence);
    }

    private static String buildRPCstatus(String mcVersion, String gameVersion) {
        String gameType = "vanilla";
        if (mcVersion.contains("forge")) {
            gameType = "forge";
        } else if (mcVersion.contains("fabric")) {
            gameType = "fabric";
        } else if (mcVersion.contains("optifine")) {
            gameType = "optifine";
        } else if (mcVersion.contains("quilt")) {
            gameType = "quilt";
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
