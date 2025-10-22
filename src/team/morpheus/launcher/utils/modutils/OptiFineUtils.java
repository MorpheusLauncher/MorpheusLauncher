package team.morpheus.launcher.utils.modutils;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import team.morpheus.launcher.Launcher;
import team.morpheus.launcher.Main;
import team.morpheus.launcher.model.products.MojangProduct;
import team.morpheus.launcher.utils.*;
import team.morpheus.launcher.utils.thread.DownloadFileTask;
import team.morpheus.launcher.utils.thread.ParallelTasks;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class OptiFineUtils {

    private static final JSONParser jsonParser = new JSONParser();

    /* Extract optifine library name from optifine json */
    public static JSONArray getOptifineModList(File jsonFile) throws IOException, ParseException {
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

    public static void doOptifineSetup(String mcVersion, File jsonFile) throws IOException, ParseException, InterruptedException {
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
                String ofVer = getOptiFineVersion(ofInstallerZip);
                String[] ofVers = tokenize(ofVer, "_");
                String mcVer = ofVers[1];
                String ofEd = getOptiFineEdition(ofVers);

                /* Make optifine library folder */
                File ofLibraryPath = new File(String.format("%s/libraries/optifine/OptiFine/%s_%s", Launcher.env.getGameFolder(), mcVer, ofEd));
                if (!ofLibraryPath.exists()) ofLibraryPath.mkdirs();

                /* Copy optifine library to libraries folder */
                File ofLibraryFile = new File(String.format("%s/OptiFine-%s_%s.jar", ofLibraryPath, mcVer, ofEd));
                copyFile(ofInstallerFile, ofLibraryFile);

                /* Retrieve launchwrapper version from txt */
                String launchwrapperVersion = getLaunchwrapperVersion(ofInstallerZip.getInputStream(ofInstallerZip.getEntry("launchwrapper-of.txt")));

                /* Build launchwrapper target folder */
                File launchwrapperPath = new File(String.format("%s/libraries/optifine/launchwrapper-of/%s", Launcher.env.getGameFolder(), launchwrapperVersion));
                if (!launchwrapperPath.exists()) launchwrapperPath.mkdirs();
                String launchwrapperFileName = String.format("launchwrapper-of-%s.jar", launchwrapperVersion);
                File launchwrapperFile = new File(String.format("%s/%s", launchwrapperPath, launchwrapperFileName));

                InputStream fin = ofInstallerZip.getInputStream(ofInstallerZip.getEntry(launchwrapperFileName));
                if (fin != null) {
                    FileOutputStream fout = new FileOutputStream(launchwrapperFile);
                    copyAll(fin, fout);
                    fout.flush();
                    fin.close();
                    fout.close();
                }

                /* Download the vanilla json if absent */
                MojangProduct.Version ver = VersionUtils.findVersion(Launcher.env.getVanilla(), mcVer);
                File vanillaJsonPath = new File(String.format("%s/versions/%s", Launcher.env.getGameFolder(), ver.id));
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

    public static String getOptiFineEdition(String[] ofVers) {
        if (ofVers.length <= 2) {
            return "";
        } else {
            String ofEd = "";
            for (int i = 2; i < ofVers.length; ++i) {
                if (i > 2) {
                    ofEd = ofEd + "_";
                }
                ofEd = ofEd + ofVers[i];
            }
            return ofEd;
        }
    }

    public static String getOptiFineVersion(ZipFile zipFile) throws IOException {
        ZipEntry zipEntry = zipFile.getEntry("net/optifine/Config.class");
        if (zipEntry == null) zipEntry = zipFile.getEntry("notch/net/optifine/Config.class");
        if (zipEntry == null) zipEntry = zipFile.getEntry("Config.class");
        if (zipEntry == null) zipEntry = zipFile.getEntry("VersionThread.class");
        if (zipEntry == null) {
            throw new IOException("OptiFine version not found");
        } else {
            InputStream in = zipFile.getInputStream(zipEntry);
            String ofVer = getOptiFineVersion(in);
            in.close();
            return ofVer;
        }
    }

    public static String getOptiFineVersion(InputStream in) throws IOException {
        byte[] bytes = readAll(in);
        byte[] pattern = "OptiFine_".getBytes("ASCII");
        int pos = find(bytes, pattern);
        if (pos < 0) {
            return null;
        } else {
            int startPos = pos;
            for (pos = pos; pos < bytes.length; ++pos) {
                byte b = bytes[pos];
                if (b < 32 || b > 122) {
                    break;
                }
            }
            String ver = new String(bytes, startPos, pos - startPos, "ASCII");
            return ver;
        }
    }

    public static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (true) {
            int len = is.read(buf);
            if (len < 0) {
                is.close();
                byte[] bytes = baos.toByteArray();
                return bytes;
            }
            baos.write(buf, 0, len);
        }
    }

    public static int find(byte[] buf, byte[] pattern) {
        return find(buf, 0, pattern);
    }

    public static int find(byte[] buf, int index, byte[] pattern) {
        for (int i = index; i < buf.length - pattern.length; ++i) {
            boolean found = true;
            for (int pos = 0; pos < pattern.length; ++pos) {
                if (pattern[pos] != buf[i + pos]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }

    public static String[] tokenize(String str, String delim) {
        List list = new ArrayList();
        StringTokenizer tok = new StringTokenizer(str, delim);
        while (tok.hasMoreTokens()) {
            String token = tok.nextToken();
            list.add(token);
        }
        String[] tokens = (String[]) list.toArray(new String[list.size()]);
        return tokens;
    }

    public static void copyAll(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[1024];
        while (true) {
            int len = is.read(buf);
            if (len < 0) {
                return;
            }
            os.write(buf, 0, len);
        }
    }

    public static void copyFile(File fileSrc, File fileDest) throws IOException {
        if (!fileSrc.getCanonicalPath().equals(fileDest.getCanonicalPath())) {
            FileInputStream fin = new FileInputStream(fileSrc);
            FileOutputStream fout = new FileOutputStream(fileDest);
            copyAll(fin, fout);
            fout.flush();
            fin.close();
            fout.close();
        }
    }

    public static String getLaunchwrapperVersion(InputStream fin) throws IOException {
        String fileLibs = "/launchwrapper-of.txt";
        if (fin == null) {
            throw new IOException("File not found: " + fileLibs);
        } else {
            String str = readText(fin, "ASCII");
            str = str.trim();
            if (!str.matches("[0-9\\.]+")) {
                throw new IOException("Invalid launchwrapper version: " + str);
            } else {
                return str;
            }
        }
    }

    public static String readText(InputStream in, String encoding) throws IOException {
        InputStreamReader inr = new InputStreamReader(in, encoding);
        BufferedReader br = new BufferedReader(inr);
        StringBuffer sb = new StringBuffer();
        while (true) {
            String line = br.readLine();
            if (line == null) {
                br.close();
                inr.close();
                in.close();
                return sb.toString();
            }
            sb.append(line);
            sb.append("\n");
        }
    }
}
