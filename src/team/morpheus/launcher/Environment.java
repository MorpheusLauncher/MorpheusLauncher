package team.morpheus.launcher;

import lombok.Getter;
import lombok.Setter;
import team.morpheus.launcher.model.LauncherVariables;
import team.morpheus.launcher.model.MojangSession;
import team.morpheus.launcher.model.products.MojangProduct;
import team.morpheus.launcher.model.products.MorpheusProduct;

import java.io.File;

public class Environment {

    @Getter
    @Setter
    private File gameFolder, assetsFolder;

    @Getter
    @Setter
    private MojangProduct vanilla;
    @Getter
    @Setter
    private MojangProduct.Version target;

    @Getter
    @Setter
    private MojangProduct.Game game; // Vanilla / Optifine / Fabric / Forge
    @Getter
    @Setter
    private MojangProduct.Game inherited; // just Vanilla (is parent of modloader)

    @Getter
    @Setter
    private MorpheusProduct.Product morpheus;

    @Getter
    @Setter
    private LauncherVariables variables;
}
