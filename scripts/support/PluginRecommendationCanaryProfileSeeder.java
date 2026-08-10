import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.extensions.PluginId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import kotlinx.serialization.DeserializationStrategy;
import kotlinx.serialization.SerializationStrategy;
import kotlinx.serialization.cbor.Cbor;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

public final class PluginRecommendationCanaryProfileSeeder {
    private static final String CACHE_MAP = "cache_v3";
    private static final String CACHE_KEY = "com.intellij.pluginAdvertiserExtensions";
    private static final String GO_EXTENSION = "*.go";
    private static final String PROPERTIES_EXTENSION = "*.properties";

    private PluginRecommendationCanaryProfileSeeder() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("verify"))) {
            throw new IllegalArgumentException("Usage: PluginRecommendationCanaryProfileSeeder <write|verify> <config-dir>");
        }

        var configDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        var database = configDirectory.resolve("app-internal-state.db");
        switch (args[0]) {
            case "write" -> {
                write(database);
            }
            case "verify" -> {
                verify(database);
            }
            default -> {
                throw new IllegalArgumentException("Unsupported command: " + args[0]);
            }
        }
    }

    private static void write(Path database) throws Exception {
        if (Files.exists(database)) {
            throw new IllegalStateException("Refusing to replace existing state database: " + database);
        }
        if (ApplicationInfoImpl.getShadowInstance().isEssentialPlugin(PluginId.getId("com.intellij.properties"))) {
            throw new IllegalStateException("Selected IDE treats com.intellij.properties as essential");
        }
        Files.createDirectories(database.getParent());

        var plugins = expectedPlugins();
        var state = newState(plugins);
        var encodedState = cbor().encodeToByteArray(serializer(), state);

        var store = new MVStore.Builder().fileName(database.toString()).open();
        try {
            openCacheMap(store).put(CACHE_KEY, encodedState);
            store.commit();
        } finally {
            store.close();
        }
        verify(database);
    }

    private static void verify(Path database) throws Exception {
        if (!Files.isRegularFile(database)) {
            throw new IllegalStateException("Missing state database: " + database);
        }

        var store = new MVStore.Builder().fileName(database.toString()).readOnly().open();
        try {
            var encodedState = openCacheMap(store).get(CACHE_KEY);
            if (encodedState == null) {
                throw new IllegalStateException("Missing advertiser cache key: " + CACHE_KEY);
            }
            var state = cbor().decodeFromByteArray(deserializer(), encodedState);
            var actualPlugins = plugins(state);
            var expectedPlugins = expectedPlugins();
            if (!actualPlugins.equals(expectedPlugins)) {
                throw new IllegalStateException("Unexpected advertiser cache: " + actualPlugins);
            }
            System.out.println("advertiser_cache=" + CACHE_KEY);
            System.out.println("cache_entries=" + actualPlugins.size());
            System.out.println(GO_EXTENSION + "=" + actualPlugins.get(GO_EXTENSION));
            System.out.println(PROPERTIES_EXTENSION + "=" + actualPlugins.get(PROPERTIES_EXTENSION));
        } finally {
            store.close();
        }
    }

    private static LinkedHashMap<String, Object> expectedPlugins() throws Exception {
        var pluginDataClass = Class.forName("com.intellij.ide.plugins.advertiser.PluginData");
        var constructor = pluginDataClass.getConstructor(
            String.class,
            String.class,
            boolean.class,
            boolean.class
        );
        var plugins = new LinkedHashMap<String, Object>();
        plugins.put(GO_EXTENSION, constructor.newInstance("org.jetbrains.plugins.go", "Go", false, false));
        plugins.put(
            PROPERTIES_EXTENSION,
            constructor.newInstance("com.intellij.properties", "Properties", true, false)
        );
        return plugins;
    }

    private static Object newState(LinkedHashMap<String, Object> plugins) throws Exception {
        var stateClass = stateClass();
        var constructor = stateClass.getDeclaredConstructor(LinkedHashMap.class);
        constructor.setAccessible(true);
        return constructor.newInstance(plugins);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> plugins(Object state) throws Exception {
        var field = stateClass().getDeclaredField("plugins");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(state);
    }

    private static Class<?> stateClass() throws ClassNotFoundException {
        return Class.forName(
            "com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertiserExtensionsState"
        );
    }

    @SuppressWarnings("unchecked")
    private static SerializationStrategy<Object> serializer() throws Exception {
        return (SerializationStrategy<Object>) serializerObject();
    }

    @SuppressWarnings("unchecked")
    private static DeserializationStrategy<Object> deserializer() throws Exception {
        return (DeserializationStrategy<Object>) serializerObject();
    }

    private static Object serializerObject() throws Exception {
        var companionField = stateClass().getDeclaredField("Companion");
        companionField.setAccessible(true);
        var companion = companionField.get(null);
        var serializerMethod = companion.getClass().getMethod("serializer");
        return serializerMethod.invoke(companion);
    }

    private static Cbor cbor() throws Exception {
        var storageHelpers = Class.forName("com.intellij.platform.settings.local.InternalStateStorageServiceKt");
        return (Cbor) storageHelpers.getMethod("getCborFormat").invoke(null);
    }

    @SuppressWarnings("unchecked")
    private static MVMap<String, byte[]> openCacheMap(MVStore store) throws Exception {
        var mapHelpers = Class.forName("com.intellij.platform.settings.local.MvMapManagerKt");
        var openMap = mapHelpers.getDeclaredMethod("access$openMap", MVStore.class, String.class);
        openMap.setAccessible(true);
        return (MVMap<String, byte[]>) openMap.invoke(null, store, CACHE_MAP);
    }
}
