package team.morpheus.launcher.utils.modutils;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import team.morpheus.launcher.Launcher;
import team.morpheus.launcher.Main;
import team.morpheus.launcher.logging.MyLogger;
import team.morpheus.launcher.utils.thread.DownloadFileTask;
import team.morpheus.launcher.utils.thread.ParallelTasks;
import team.morpheus.launcher.utils.Utils;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
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
        doForgeUnpack(forgeInstallerFile, jsonFile, forgeInstallerVersion);
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
    public static void doForgeUnpack(File forgeInstallerFile, File jsonFile, String forgeLibName) throws ParseException, IOException {
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
                try (InputStream inputStream = zipFile.getInputStream(entry); OutputStream outputStream = Files.newOutputStream(Paths.get(jsonFile.getPath()))) {
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
                File libFolder = makeDirectory(String.format("%s/libraries", Launcher.env.getGameFolder().getPath()));

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
}
