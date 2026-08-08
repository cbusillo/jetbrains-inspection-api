package com.shiny.inspectionmcp;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivatePluginRecommendationBoundaryTest {
    private static final String BOUNDARY_CLASS =
        "com.shiny.inspectionmcp.PrivatePluginRecommendationBoundary";
    private static final String FALLBACK_CLASS =
        "com.shiny.inspectionmcp.PrivatePluginRecommendationFallback";
    private static final String PROBE_CLASS =
        "com.shiny.inspectionmcp.PrivatePluginRecommendationProbe";

    @Test
    void preEntryClassLinkFailureReturnsCompleteUnavailableResponse() throws Exception {
        VirtualFile file = file("/repo/main.go", "main.go", "PLAIN_TEXT");
        ClassLoader rejectingLoader = new ProbeRejectingClassLoader(getClass().getClassLoader());
        Class<?> boundaryClass = Class.forName(BOUNDARY_CLASS, true, rejectingLoader);
        Method probe = boundaryClass.getDeclaredMethod("probe", Project.class, List.class);
        probe.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) probe.invoke(
            null,
            mock(Project.class),
            List.of(file)
        );

        assertUnavailableResponse(result, "NoClassDefFoundError");
    }

    @Test
    void preEntryInitializerFailureReturnsCompleteUnavailableResponse() {
        VirtualFile file = file("/repo/main.go", "main.go", "PLAIN_TEXT");

        Map<String, Object> result = PrivatePluginRecommendationFallback.setupFailureResponse(
            List.of(file),
            new ExceptionInInitializerError(new IllegalStateException("private initializer failed"))
        );

        assertUnavailableResponse(result, "ExceptionInInitializerError");
    }

    @Test
    void wrappedCancellationAndFatalVmErrorsStillPropagate() {
        VirtualFile file = file("/repo/main.go", "main.go", "PLAIN_TEXT");

        assertThrows(
            ProcessCanceledException.class,
            () -> PrivatePluginRecommendationFallback.setupFailureResponse(
                List.of(file),
                new ExceptionInInitializerError(new ProcessCanceledException())
            )
        );
        assertThrows(
            OutOfMemoryError.class,
            () -> PrivatePluginRecommendationFallback.setupFailureResponse(
                List.of(file),
                new ExceptionInInitializerError(new OutOfMemoryError("fatal"))
            )
        );
    }

    @Test
    void handlerAndFallbackRemainFreeOfPrivateAdvertiserLinks() throws Exception {
        String boundaryPool = constantPool(PrivatePluginRecommendationBoundary.class);
        String fallbackPool = constantPool(PrivatePluginRecommendationFallback.class);
        String handlerPool = constantPool(InspectionHandler.class);
        String handlerKtPool = constantPool(Class.forName("com.shiny.inspectionmcp.InspectionHandlerKt"));

        assertFalse(boundaryPool.contains("pluginsAdvertisement"));
        assertFalse(boundaryPool.contains("PluginData"));
        assertFalse(fallbackPool.contains("pluginsAdvertisement"));
        assertFalse(fallbackPool.contains("PluginData"));
        assertFalse(fallbackPool.contains("PrivatePluginRecommendationProbe"));
        assertFalse(handlerPool.contains("pluginsAdvertisement"));
        assertFalse(handlerPool.contains("PrivatePluginRecommendationProbe"));
        assertTrue(handlerPool.contains("PrivatePluginRecommendationBoundary"));
        assertFalse(handlerKtPool.contains("pluginsAdvertisement"));
        assertFalse(handlerKtPool.contains("PrivatePluginRecommendationProbe"));
    }

    @SuppressWarnings("unchecked")
    private static void assertUnavailableResponse(Map<String, Object> result, String detail) {
        assertEquals("ok", result.get("status"));
        assertEquals(1, result.get("schema_version"));
        assertEquals("jetbrains_private_plugin_advertiser_262", result.get("source"));
        assertEquals("private_internal", result.get("api_status"));
        assertEquals("262", result.get("platform_line"));
        assertEquals(true, result.get("read_only"));
        assertEquals("unavailable", result.get("coverage"));
        assertEquals(1, result.get("files_requested"));
        assertEquals("private_api_setup_failure", result.get("reason"));
        assertEquals(detail, result.get("detail"));

        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        assertEquals(1, files.size());
        assertEquals("/repo/main.go", files.getFirst().get("path"));
        assertEquals("main.go", files.getFirst().get("name"));
        assertEquals("PLAIN_TEXT", files.getFirst().get("file_type"));
        assertEquals("unavailable", files.getFirst().get("state"));
        assertEquals("private_api_setup_failure", files.getFirst().get("reason"));
        assertEquals(detail, files.getFirst().get("detail"));
    }

    private static String constantPool(Class<?> targetClass) throws Exception {
        String resourceName = targetClass.getName().replace('.', '/') + ".class";
        byte[] classBytes = Objects.requireNonNull(
            targetClass.getClassLoader().getResourceAsStream(resourceName),
            resourceName
        ).readAllBytes();
        return new String(classBytes, StandardCharsets.ISO_8859_1);
    }

    private static byte[] classBytes(String className) throws Exception {
        String resourceName = className.replace('.', '/') + ".class";
        return Objects.requireNonNull(
            PrivatePluginRecommendationBoundaryTest.class.getClassLoader().getResourceAsStream(resourceName),
            resourceName
        ).readAllBytes();
    }

    private static VirtualFile file(String path, String name, String fileTypeName) {
        VirtualFile file = mock(VirtualFile.class);
        FileType fileType = mock(FileType.class);
        when(file.getPath()).thenReturn(path);
        when(file.getName()).thenReturn(name);
        when(file.getFileType()).thenReturn(fileType);
        when(fileType.getName()).thenReturn(fileTypeName);
        return file;
    }

    private static final class ProbeRejectingClassLoader extends ClassLoader {
        private ProbeRejectingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (PROBE_CLASS.equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                if (BOUNDARY_CLASS.equals(name) || FALLBACK_CLASS.equals(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try {
                            byte[] bytes = classBytes(name);
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (Exception error) {
                            throw new ClassNotFoundException(name, error);
                        }
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        }
    }
}
