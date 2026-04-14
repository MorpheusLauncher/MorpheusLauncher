package team.morpheus.launcher.utils.modutils;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import team.morpheus.launcher.Launcher;
import team.morpheus.launcher.Main;
import team.morpheus.launcher.logging.MyLogger;
import team.morpheus.launcher.utils.Utils;
import team.morpheus.launcher.utils.thread.DownloadFileTask;
import team.morpheus.launcher.utils.thread.ParallelTasks;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static team.morpheus.launcher.utils.Utils.makeDirectory;

public class ForgeUtils {

    private static final MyLogger log = new MyLogger(ForgeUtils.class);

    public static void doForgeSetup(String mcLowercase, File jsonFile) throws IOException, ParseException, InterruptedException {
        /* Fetch forge versions */
        String forgeVersionList = Utils.makeGetRequest(new URL(Main.getForgeVersionsURL()));
        log.info("Fetching available forge versions");

        /* Setup json parsing */
        String forgeInstallerVersion = getString(mcLowercase, forgeVersionList);

        URL forgeInstallerUrl = new URL(String.format("%s%s/forge-%s-installer.jar", Main.getForgeInstallerURL(), forgeInstallerVersion, forgeInstallerVersion));
        File forgeInstallerFile = new File(String.format("%s/forge-%s-installer.jar", System.getProperty("java.io.tmpdir"), forgeInstallerVersion));

        /* Download latest forge for the selected minecraft version*/
        if (!forgeInstallerFile.exists()) {
            ParallelTasks tasks = new ParallelTasks();
            tasks.add(new DownloadFileTask(forgeInstallerUrl, forgeInstallerFile.getPath()));
            tasks.go();
        }
        doForgeUnpack(forgeInstallerFile, jsonFile, forgeInstallerVersion, Launcher.env.getGameFolder());
    }

    private static String getString(String mcLowercase, String forgeVersionList) throws ParseException {
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
        return candidate.get(candidate.size() - 1);
    }

    /* This function handles the unpacking of the forge installer */
    public static void doForgeUnpack(File forgeInstallerFile, File jsonFile, String forgeLibName, File gameFolder) throws ParseException, IOException, InterruptedException {
        String[] forgeVersion = forgeLibName.split("-");
        StringBuilder installProfileContent = new StringBuilder();

        /* This will contain the jar archive name to be extracted (old format only) */
        String forgeFilePath = "";
        /* This will contain the path to jar will extracted (old format only) */
        String forgeTargetPath = "";

        JSONObject installProfile = null;

        /* Search and read files from installer jar */
        ZipFile zipFile = new ZipFile(forgeInstallerFile);
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();

            /* Search the installation json file that describes how forge should be installed */
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
                installProfile = (JSONObject) obj;

                /* All versionInfo values will be saved as dedicated json in versions folder (old format 1.6.4 → 1.11.2) */
                JSONObject versionInfo = (JSONObject) installProfile.get("versionInfo");
                /* This array will contain the installation details (old format) */
                JSONObject installInfo = (JSONObject) installProfile.get("install");

                /* starting from (1.12.2) */
                if (installProfile.get("path") != null) {
                    forgeTargetPath = installProfile.get("path").toString();
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
                /* New format (1.13+): version.json is a standalone file */
                try (InputStream inputStream = zipFile.getInputStream(entry); OutputStream outputStream = Files.newOutputStream(Paths.get(jsonFile.getPath()))) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, length);
                    }
                }
            }
        }

        /* ------------------------------------------------------------------ *
         * New Forge format detection (spec=1, has "processors" array)         *
         * ------------------------------------------------------------------ */
        boolean isNewFormat = installProfile != null && installProfile.containsKey("processors");

        if (isNewFormat) {
            log.info("Detected new Forge installer format (spec=1), running processors pipeline...");
            runNewForgeInstallerPipeline(forgeInstallerFile, installProfile, gameFolder, zipFile);
        } else {
            /* Old format: extract embedded forge JAR from installer zip */
            entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().contains(".jar")) {
                    File libFolder = makeDirectory(String.format("%s/libraries", gameFolder.getPath()));

                    String[] namesplit = forgeTargetPath.split(":");
                    File libpath = makeDirectory(String.format("%s/%s/%s/%s/", libFolder.getPath(), namesplit[0].replace(".", "/"), namesplit[1], namesplit[2]));
                    File libfile = new File(String.format("%s/%s-%s.jar", libpath.getPath(), namesplit[1], namesplit[2]));

                    if (libfile.createNewFile())
                        try (InputStream inputStream = zipFile.getInputStream(entry); OutputStream outputStream = Files.newOutputStream(libfile.toPath())) {
                            byte[] buffer = new byte[1024];
                            int length;
                            while ((length = inputStream.read(buffer)) > 0) {
                                outputStream.write(buffer, 0, length);
                            }
                        }
                }
            }
        }

        zipFile.close();
    }

    /* ====================================================================== *
     * NEW FORGE INSTALLER PIPELINE (Forge 1.13+)                             *
     *                                                                         *
     * This runs the processor JARs listed in install_profile.json to:        *
     *  1. Download Mojang mappings                                            *
     *  2. Remap the vanilla client JAR                                        *
     *  3. Apply binary patches to produce forge-client.jar                   *
     * ====================================================================== */
    @SuppressWarnings("unchecked")
    private static void runNewForgeInstallerPipeline(File installerJar, JSONObject installProfile, File gameFolder, ZipFile zipFile) throws IOException, InterruptedException {

        File librariesDir = makeDirectory(String.format("%s/libraries", gameFolder.getPath()));

        /* ---- 1. Download all processor libraries from install_profile.json ---- */
        log.info("[Forge] Downloading processor libraries...");
        JSONArray profileLibraries = (JSONArray) installProfile.get("libraries");
        if (profileLibraries != null) {
            ParallelTasks downloadTasks = new ParallelTasks();
            for (Object libObj : profileLibraries) {
                JSONObject lib = (JSONObject) libObj;
                JSONObject downloads = (JSONObject) lib.get("downloads");
                if (downloads == null) continue;
                JSONObject artifact = (JSONObject) downloads.get("artifact");
                if (artifact == null) continue;

                String path = (String) artifact.get("path");
                String url = (String) artifact.get("url");
                if (path == null || url == null || url.isEmpty()) continue;

                File dest = new File(librariesDir, path);
                if (!dest.exists()) {
                    dest.getParentFile().mkdirs();
                    downloadTasks.add(new DownloadFileTask(new URL(url), dest.getPath()));
                }
            }
            downloadTasks.go();
        }

        /* ---- 2. Resolve variable substitutions ---- */
        /*
         * Variables used by the processors (from "data" section):
         *   {BINPATCH}     → /data/client.lzma  (inside the installer JAR)
         *   {MOJMAPS}      → artifact path of net.minecraft:client:mappings
         *   {MOJMAPS_SHA}  → expected sha1 (literal string in quotes)
         *   {MC_UNPACKED}  → artifact path of net.minecraft:client (not needed client-side)
         *   {MC_OFF}       → artifact path of net.minecraft:client:official
         *   {MC_UNPACKED_SHA}, {MC_OFF_SHA}, etc.
         *   {PATCHED}      → artifact path of forge:client (the output)
         *   {PATCHED_SHA}  → expected sha1 of PATCHED
         */
        JSONObject dataSection = (JSONObject) installProfile.get("data");
        Map<String, String> vars = new HashMap<>();

        // Standard variables
        vars.put("{SIDE}", "client");
        vars.put("{LIBRARY_DIR}", librariesDir.getAbsolutePath() + File.separator);
        vars.put("{INSTALLER}", installerJar.getAbsolutePath());
        vars.put("{ROOT}", gameFolder.getAbsolutePath());

        // Variables from install_profile.json "data" section — pick the "client" value
        if (dataSection != null) {
            for (Object keyObj : dataSection.keySet()) {
                String key = (String) keyObj;
                JSONObject sides = (JSONObject) dataSection.get(key);
                Object clientVal = sides.get("client");
                if (clientVal != null) {
                    vars.put("{" + key + "}", clientVal.toString());
                }
            }
        }

        /* ---- 3. Resolve artifact references [group:artifact:version:classifier@ext] → file path ---- */
        /* Artifact refs look like [net.minecraft:client:1.21.11:mappings@tsrg] */
        /* Strip literal string vars (surrounded by single quotes) → take value as-is */

        String mcVersion = (String) installProfile.get("minecraft");

        /* ---- 4. Materialise {BINPATCH} by extracting client.lzma from installer ZIP ---- */
        String binpatchVar = vars.get("{BINPATCH}");
        if (binpatchVar != null) {
            // Strip leading slash → "data/client.lzma"
            String entryName = binpatchVar.startsWith("/") ? binpatchVar.substring(1) : binpatchVar;
            File lzmaTemp = new File(System.getProperty("java.io.tmpdir"), "forge_client_" + mcVersion + ".lzma");
            if (!lzmaTemp.exists()) {
                ZipEntry lzmaEntry = zipFile.getEntry(entryName);
                if (lzmaEntry != null) {
                    try (InputStream is = zipFile.getInputStream(lzmaEntry); OutputStream os = new FileOutputStream(lzmaTemp)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                    }
                    log.info("[Forge] Extracted " + entryName + " to " + lzmaTemp.getPath());
                }
            }
            vars.put("{BINPATCH}", lzmaTemp.getAbsolutePath());
        }

        /* ---- 5. Resolve artifact-format variables into library paths ---- */
        // After binpatch, resolve the remaining [artifact] references
        Map<String, String> resolvedVars = new HashMap<>(vars);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String val = e.getValue();
            if (val.startsWith("[") && val.endsWith("]")) {
                // artifact reference: resolve to library path
                String coord = val.substring(1, val.length() - 1);
                String resolved = new File(librariesDir, coordToPath(coord)).getAbsolutePath();
                resolvedVars.put(e.getKey(), resolved);
            } else if (val.startsWith("'") && val.endsWith("'")) {
                // literal string: strip quotes
                resolvedVars.put(e.getKey(), val.substring(1, val.length() - 1));
            }
        }

        /* ---- 6. Find vanilla client JAR for {MINECRAFT_JAR}, download if missing ---- */
        File vanillaJar = new File(String.format("%s/versions/%s/%s.jar", gameFolder.getPath(), mcVersion, mcVersion));
        ensureVanillaJar(mcVersion, vanillaJar);
        resolvedVars.put("{MINECRAFT_JAR}", vanillaJar.getAbsolutePath());

        /* ---- 7. Run processors filtered to client side ---- */
        JSONArray processors = (JSONArray) installProfile.get("processors");
        if (processors == null) return;

        String javaExecutable = "java";
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            String[] candidates = {javaHome + File.separator + "bin" + File.separator + "java", javaHome + File.separator + "bin" + File.separator + "java.exe", javaHome + File.separator + ".." + File.separator + "bin" + File.separator + "java", javaHome + File.separator + ".." + File.separator + "bin" + File.separator + "java.exe"};
            for (String candidate : candidates) {
                File f = new File(candidate);
                if (f.exists()) {
                    javaExecutable = f.getAbsolutePath();
                    break;
                }
            }
        }
        Map<File, String> mainClassCache = new HashMap<>();

        for (Object procObj : processors) {
            JSONObject proc = (JSONObject) procObj;

            /* Skip processors that are server-only */
            JSONArray sides = (JSONArray) proc.get("sides");
            if (sides != null) {
                boolean hasClient = false;
                for (Object s : sides) {
                    if ("client".equals(s.toString())) {
                        hasClient = true;
                        break;
                    }
                }
                if (!hasClient) {
                    log.info("[Forge] Skipping server-only processor");
                    continue;
                }
            }

            /* Check outputs — if all output files already exist skip this processor */
            JSONObject outputs = (JSONObject) proc.get("outputs");
            if (outputs != null) {
                boolean allOutputsExist = true;
                for (Object outKey : outputs.keySet()) {
                    String outPath = resolveVar(outKey.toString(), resolvedVars);
                    File outFile = new File(outPath);
                    if (!outFile.exists()) {
                        allOutputsExist = false;
                        break;
                    }
                }
                if (allOutputsExist) {
                    log.info("[Forge] Processor outputs already exist, skipping");
                    continue;
                }
            }

            /* Build classpath: processor JAR + its declared classpath */
            String processorJarCoord = (String) proc.get("jar");
            File processorJar = new File(librariesDir, coordToPath(processorJarCoord));

            StringBuilder classpath = new StringBuilder(processorJar.getAbsolutePath());
            JSONArray cpList = (JSONArray) proc.get("classpath");
            if (cpList != null) {
                for (Object cp : cpList) {
                    File cpJar = new File(librariesDir, coordToPath(cp.toString()));
                    classpath.append(File.pathSeparator).append(cpJar.getAbsolutePath());
                }
            }

            /* Resolve processor arguments */
            JSONArray argsList = (JSONArray) proc.get("args");
            List<String> args = new ArrayList<>();
            if (argsList != null) {
                for (Object arg : argsList) {
                    args.add(resolveVar(arg.toString(), resolvedVars));
                }
            }

            /* Read Main-Class from the processor JAR manifest */
            String mainClass = mainClassCache.get(processorJar);
            if (mainClass == null) {
                if (processorJar.exists()) {
                    try (ZipFile zf = new ZipFile(processorJar)) {
                        ZipEntry manifest = zf.getEntry("META-INF/MANIFEST.MF");
                        if (manifest != null) {
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(zf.getInputStream(manifest)))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.startsWith("Main-Class:")) {
                                        mainClass = line.substring("Main-Class:".length()).trim();
                                        mainClassCache.put(processorJar, mainClass);
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            if (mainClass == null) {
                log.error("[Forge] Cannot determine main class for " + processorJarCoord + ", skipping");
                continue;
            }

            /* Build and run the command */
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExecutable);
            cmd.add("-cp");
            cmd.add(classpath.toString());
            cmd.add(mainClass);
            cmd.addAll(args);

            log.info("[Forge] Running processor: " + processorJarCoord);
            log.info("[Forge] Command: " + String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("[Forge] Processor " + processorJarCoord + " failed with exit code " + exitCode);
            }
            log.info("[Forge] Processor completed: " + processorJarCoord);
        }

        log.info("[Forge] All client processors completed successfully!");
    }

    /**
     * Downloads the vanilla client JAR from Mojang if it is not already present.
     * Queries the Mojang version manifest to find the exact download URL.
     */
    private static void ensureVanillaJar(String mcVersion, File vanillaJar) throws IOException, InterruptedException {
        if (vanillaJar.exists()) {
            log.info("[Forge] Vanilla JAR already present: " + vanillaJar.getPath());
            return;
        }

        log.info("[Forge] Vanilla JAR not found, fetching from Mojang manifest...");
        String manifestJson = Utils.makeGetRequest(new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json"));

        JSONParser parser = new JSONParser();
        JSONObject manifest;
        try {
            manifest = (JSONObject) parser.parse(manifestJson);
        } catch (ParseException e) {
            throw new IOException("[Forge] Failed to parse Mojang version manifest", e);
        }

        JSONArray versions = (JSONArray) manifest.get("versions");
        String versionUrl = null;
        for (Object vObj : versions) {
            JSONObject v = (JSONObject) vObj;
            if (mcVersion.equals(v.get("id"))) {
                versionUrl = (String) v.get("url");
                break;
            }
        }

        if (versionUrl == null) {
            throw new IOException("[Forge] Cannot find vanilla version '" + mcVersion + "' in Mojang manifest");
        }

        // Download and parse the version JSON to get the client JAR URL
        String versionJson = Utils.makeGetRequest(new URL(versionUrl));
        JSONObject versionObj;
        try {
            versionObj = (JSONObject) parser.parse(versionJson);
        } catch (ParseException e) {
            throw new IOException("[Forge] Failed to parse version JSON for " + mcVersion, e);
        }

        JSONObject downloads = (JSONObject) versionObj.get("downloads");
        JSONObject clientDownload = (JSONObject) downloads.get("client");
        String clientUrl = (String) clientDownload.get("url");

        vanillaJar.getParentFile().mkdirs();
        log.info("[Forge] Downloading vanilla JAR for " + mcVersion + " from " + clientUrl);
        ParallelTasks tasks = new ParallelTasks();
        tasks.add(new DownloadFileTask(new URL(clientUrl), vanillaJar.getPath()));
        tasks.go();
        log.info("[Forge] Vanilla JAR downloaded to " + vanillaJar.getPath());
    }

    /**
     * Converts a Maven coordinate (group:artifact:version[:classifier][@ext])
     * to a relative library path, e.g.
     * net.minecraftforge:binarypatcher:1.2.0 → net/minecraftforge/binarypatcher/1.2.0/binarypatcher-1.2.0.jar
     * net.minecraft:client:1.21.11:mappings@tsrg → net/minecraft/client/1.21.11/client-1.21.11-mappings.tsrg
     */
    private static String coordToPath(String coord) {
        // Split off extension if present (@ext)
        String ext = "jar";
        if (coord.contains("@")) {
            int at = coord.lastIndexOf('@');
            ext = coord.substring(at + 1);
            coord = coord.substring(0, at);
        }
        String[] parts = coord.split(":");
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? parts[3] : null;

        String filename = classifier != null ? artifact + "-" + version + "-" + classifier + "." + ext : artifact + "-" + version + "." + ext;

        return group + "/" + artifact + "/" + version + "/" + filename;
    }

    /**
     * Replaces all {VAR} tokens in a string with their resolved values.
     * Also resolves bare artifact references [group:artifact:version@ext]
     * that appear directly as processor arguments (not inside a {VAR}).
     */
    private static String resolveVar(String input, Map<String, String> vars) {
        String result = input;

        // Substitute {VAR} tokens from the resolved vars map
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (result.contains(e.getKey())) {
                result = result.replace(e.getKey(), e.getValue());
            }
        }

        // If the whole argument (after substitution) is still a bare artifact
        // reference like [group:artifact:version@ext], resolve it to a library path
        if (result.startsWith("[") && result.endsWith("]")) {
            String coord = result.substring(1, result.length() - 1);
            // librariesDir is not directly available here, so we reconstruct it
            // from {LIBRARY_DIR} which was placed into the vars map earlier
            String libDir = vars.get("{LIBRARY_DIR}");
            if (libDir != null) {
                result = new File(libDir, coordToPath(coord)).getAbsolutePath();
            }
        }

        return result;
    }
}
