package team.morpheus.launcher.starters.impl;

import team.morpheus.launcher.logging.MyLogger;
import team.morpheus.launcher.model.products.MojangProduct;
import team.morpheus.launcher.starters.ILibraryManager;
import team.morpheus.launcher.utils.Utils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

public class ClassloaderLauncher implements ILibraryManager {

    private final MyLogger log = new MyLogger(ClasspathLauncher.class);
    private final MojangProduct.Game game;
    private final List<URL> paths;

    public ClassloaderLauncher(MojangProduct.Game game, List<URL> paths) {
        this.game = game;
        this.paths = paths;
    }

    @Override
    public MojangProduct.Game getGame() {
        return game;
    }

    @Override
    public List<URL> getPaths() {
        return paths;
    }

    @Override
    public void launch(List<String> gameargs) throws Exception {
        log.info("Launching game with classloader");

        /* Add all url paths to class loader */
        URLClassLoader ucl = new URLClassLoader(getPaths().toArray(new URL[getPaths().size()]));
        Thread.currentThread().setContextClassLoader(ucl);
        Class<?> c = ucl.loadClass(getGame().mainClass);

        /* Mangle game arguments */
        String[] args = new String[]{};
        String[] concat = Utils.concat(gameargs.toArray(new String[gameargs.size()]), args);
        String[] startArgs = Arrays.copyOfRange(concat, 0, concat.length);

        /* Method Handle instead of reflection, to make compatible with jre higher than 8 */
        MethodHandle mainMethodHandle = MethodHandles.lookup().findStatic(c, "main", MethodType.methodType(void.class, String[].class));

        /* Invoke the main with the given arguments */
        try {
            log.debug(String.format("Invoking: %s", c.getName()));
            mainMethodHandle.invokeExact(startArgs);
        } catch (Throwable e) {
            if (e.getMessage() != null) log.error(e.getMessage());
            e.printStackTrace();
        }
    }
}
