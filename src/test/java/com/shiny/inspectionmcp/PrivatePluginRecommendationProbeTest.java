package com.shiny.inspectionmcp;

import com.intellij.ide.plugins.advertiser.PluginData;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.NoSuggestions;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertisedByFileName;
import com.intellij.openapi.vfs.VirtualFile;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivatePluginRecommendationProbeTest {
    @Test
    void describesPrivateFileNameRecommendationWithBoundedMetadata() {
        VirtualFile file = file("/repo/main.go", "main.go", "PLAIN_TEXT");
        PluginData plugin = new PluginData("org.jetbrains.plugins.go", "Go", false, false);
        PluginAdvertisedByFileName suggestion = new PluginAdvertisedByFileName("*.go", Set.of(plugin));

        Map<String, Object> result = PrivatePluginRecommendationProbe.describeSuggestion(
            file,
            suggestion,
            ignored -> "absent"
        );

        assertEquals("recommended", result.get("state"));
        assertEquals(Map.of("kind", "file_name_or_extension", "value", "*.go"), result.get("trigger"));
        assertEquals(
            java.util.List.of(Map.of(
                "plugin_id", "org.jetbrains.plugins.go",
                "plugin_name", "Go",
                "installation_state", "absent"
            )),
            result.get("plugins")
        );
        assertFalse(result.containsKey("inspection_verdict"));
        assertFalse(result.containsKey("retry_policy"));
    }

    @Test
    void distinguishesNoRecommendationFromUnavailableCache() {
        VirtualFile file = file("/repo/readme.txt", "readme.txt", "PLAIN_TEXT");

        Map<String, Object> none =
            PrivatePluginRecommendationProbe.describeSuggestion(file, NoSuggestions.INSTANCE);
        Map<String, Object> unavailable =
            PrivatePluginRecommendationProbe.describeSuggestion(file, null);

        assertEquals("none", none.get("state"));
        assertEquals("no_recommendation", none.get("reason"));
        assertEquals("unavailable", unavailable.get("state"));
        assertEquals("recommendation_cache_unavailable", unavailable.get("reason"));
        assertTrue(none.keySet().stream().noneMatch(key -> key.startsWith("inspection_")));
        assertTrue(unavailable.keySet().stream().noneMatch(key -> key.startsWith("inspection_")));
    }

    @Test
    void filtersEnabledPluginsButPreservesDisabledRecommendations() {
        VirtualFile file = file("/repo/main.js", "main.js", "JavaScript");
        PluginData plugin = new PluginData("JavaScript", "JavaScript", false, false);
        PluginAdvertisedByFileName suggestion = new PluginAdvertisedByFileName("*.js", Set.of(plugin));

        Map<String, Object> enabled = PrivatePluginRecommendationProbe.describeSuggestion(
            file,
            suggestion,
            ignored -> "enabled"
        );
        Map<String, Object> disabled = PrivatePluginRecommendationProbe.describeSuggestion(
            file,
            suggestion,
            ignored -> "disabled"
        );

        assertEquals("none", enabled.get("state"));
        assertEquals("recommended_plugin_already_enabled", enabled.get("reason"));
        assertEquals(
            java.util.List.of(Map.of(
                "plugin_id", "JavaScript",
                "plugin_name", "JavaScript",
                "installation_state", "enabled"
            )),
            enabled.get("plugins")
        );
        assertEquals("recommended", disabled.get("state"));
        assertEquals(
            java.util.List.of(Map.of(
                "plugin_id", "JavaScript",
                "plugin_name", "JavaScript",
                "installation_state", "disabled"
            )),
            disabled.get("plugins")
        );
    }

    @Test
    void emptyPrivatePluginSetIsUnavailableRatherThanEnabled() {
        VirtualFile file = file("/repo/main.go", "main.go", "textmate");
        PluginAdvertisedByFileName suggestion = new PluginAdvertisedByFileName("*.go", Set.of());

        Map<String, Object> result = PrivatePluginRecommendationProbe.describeSuggestion(
            file,
            suggestion,
            ignored -> "enabled"
        );

        assertEquals("unavailable", result.get("state"));
        assertEquals("recommendation_plugin_metadata_unavailable", result.get("reason"));
    }

    @Test
    void probeBytecodeCannotReachTrustedInspectionOrLifecycleState() throws Exception {
        String resourceName = PrivatePluginRecommendationProbe.class.getName().replace('.', '/') + ".class";
        byte[] classBytes = PrivatePluginRecommendationProbe.class.getClassLoader()
            .getResourceAsStream(resourceName)
            .readAllBytes();
        String constantPool = new String(classBytes, StandardCharsets.ISO_8859_1);

        assertFalse(constantPool.contains("InspectionResultsStore"));
        assertFalse(constantPool.contains("InspectionRunState"));
        assertFalse(constantPool.contains("inspection_verdict"));
        assertFalse(constantPool.contains("retry_policy"));
        assertFalse(constantPool.contains("Lifecycle"));
        assertFalse(constantPool.contains("cleanup"));
        assertFalse(constantPool.contains("putCopyableUserData"));
        assertFalse(constantPool.contains("registerUnknownFeature"));
        assertFalse(constantPool.contains("updateCache"));
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
}
