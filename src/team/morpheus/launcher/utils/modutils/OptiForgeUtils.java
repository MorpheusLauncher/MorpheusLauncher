package team.morpheus.launcher.utils.modutils;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import team.morpheus.launcher.Launcher;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import static team.morpheus.launcher.utils.modutils.ForgeUtils.doForgeSetup;
import static team.morpheus.launcher.utils.modutils.OptiFineUtils.doOptifineSetup;
import static team.morpheus.launcher.utils.modutils.OptiFineUtils.getOptifineModList;

public class OptiForgeUtils {

    private static final JSONParser jsonParser = new JSONParser();

    public static void doOptiForgeSetup(String mcVersion, File jsonFile) throws IOException, ParseException, InterruptedException {
        /* Install optifine */
        doOptifineSetup(mcVersion, jsonFile);

        /* Make the modlist json */
        String modlistName = String.format("tempModList-%s.json", mcVersion);
        File modlistFile = new File(String.format("%s/%s", Launcher.env.getGameFolder(), modlistName));
        JSONObject optiJsonObject = new JSONObject();
        optiJsonObject.put("repositoryRoot", String.format("%s/libraries", Launcher.env.getGameFolder()));
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
}
