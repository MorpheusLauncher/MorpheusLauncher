package team.morpheus.launcher.utils;

import team.morpheus.launcher.Main;
import team.morpheus.launcher.logging.MyLogger;
import team.morpheus.launcher.utils.modutils.ForgeUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Utils {

    private static final MyLogger log = new MyLogger(Utils.class);

    public static File makeDirectory(String path) {
        File temp = new File(path);
        if (!temp.exists() && temp.mkdirs()) {
            log.info(String.format("Directory created: %s", temp.getPath()));
        }
        return temp;
    }

    public static <T> T[] concat(T[] first, T[] second) {
        T[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    public static String makeGetRequest(URL url) throws IOException {
        HttpURLConnection httpurlconnection = (HttpURLConnection) url.openConnection();
        httpurlconnection.setRequestMethod("GET");
        httpurlconnection.setRequestProperty("User-Agent", String.format("Morpheus Launcher (%s)", Main.build));
        httpurlconnection.setUseCaches(false);
        BufferedReader bufferedreader = new BufferedReader(new InputStreamReader(httpurlconnection.getInputStream()));
        StringBuilder stringbuilder = new StringBuilder();
        String s;

        while ((s = bufferedreader.readLine()) != null) {
            stringbuilder.append(s);
            stringbuilder.append('\r');
        }

        bufferedreader.close();
        return stringbuilder.toString();
    }

    public static void downloadAndUnzipNatives(URL source, File targetPath, MyLogger log) {
        try {
            ZipInputStream zipInputStream = new ZipInputStream(source.openStream());
            ZipEntry zipEntry = zipInputStream.getNextEntry();
            while (zipEntry != null) {
                if (!zipEntry.isDirectory()) {
                    String fileName = zipEntry.getName().replace("\\", "/");
                    String[] fileSplit = fileName.split("/");
                    File newFile = new File(targetPath.getPath(), fileSplit[Math.max(0, fileSplit.length - 1)]);

                    boolean isNativeFile = newFile.getPath().endsWith(".dll") || newFile.getPath().endsWith(".dylib") || newFile.getPath().endsWith(".jnilib") || newFile.getPath().endsWith(".so");
                    if (isNativeFile) {
                        FileOutputStream fileOutputStream = new FileOutputStream(newFile);
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = zipInputStream.read(buffer)) > 0) {
                            fileOutputStream.write(buffer, 0, length);
                        }
                        fileOutputStream.close();
                    }
                }
                zipEntry = zipInputStream.getNextEntry();
            }
            zipInputStream.closeEntry();
            zipInputStream.close();
        } catch (Exception e) {
            log.error(e.getMessage());
            e.printStackTrace();
        }
    }
}
