package team.morpheus.launcher.instance;

import com.google.gson.Gson;
import team.morpheus.launcher.Launcher;
import team.morpheus.launcher.Main;
import team.morpheus.launcher.logging.MyLogger;
import team.morpheus.launcher.model.LauncherVariables;
import team.morpheus.launcher.model.products.MorpheusProduct;
import team.morpheus.launcher.utils.Utils;

import java.io.IOException;
import java.net.URL;

public class Morpheus {

    private static final MyLogger log = new MyLogger(Morpheus.class);
    private String version;
    private boolean startonfirstthread;

    public Morpheus(String version, boolean startonfirstthread) {
        this.version = version;
        this.startonfirstthread = startonfirstthread;
    }

    public void prepareLaunch(String gamePath) throws Exception {
        String baseUrl = String.format("%s/downloads/morpheus-lite", Main.getMorpheusAPI());
        String indexUrl = String.format("%s/index.json", baseUrl);

        Gson gson = new Gson();
        String jsonResponse;
        try {
            jsonResponse = Utils.makeGetRequest(new URL(indexUrl));
        } catch (IOException e) {
            log.error("Could not find morpheus website");
            return;
        }
        MorpheusProduct prods = gson.fromJson(jsonResponse, MorpheusProduct.class);

        // Split version string
        String[] split = version.split("-");

        MorpheusProduct.Product morpheusProduct = null;
        for (MorpheusProduct.Product prod : prods.products) {
            if (prod.gameversion.equals(split[1])) {
                morpheusProduct = prod;
                break;
            }
        }
        if (morpheusProduct == null) {
            log.error("Could not find morpheus version");
            return;
        }

        log.info(String.format("Launching morpheus instance (%s)", morpheusProduct.gameversion));
        new Launcher(new LauncherVariables(morpheusProduct.gameversion, gamePath, startonfirstthread), morpheusProduct).launchGame();
    }
}