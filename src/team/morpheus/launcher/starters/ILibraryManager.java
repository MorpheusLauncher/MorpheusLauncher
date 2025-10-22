package team.morpheus.launcher.starters;

import team.morpheus.launcher.model.products.MojangProduct;

import java.net.URL;
import java.util.List;

public interface ILibraryManager {

    MojangProduct.Game getGame();

    List<URL> getPaths();

    void launch(List<String> args) throws Exception;
}
