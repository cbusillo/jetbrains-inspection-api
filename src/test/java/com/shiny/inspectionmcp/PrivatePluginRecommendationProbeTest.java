package com.shiny.inspectionmcp;

import com.intellij.ide.plugins.advertiser.PluginData;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.NoSuggestions;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertisedByFileContent;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertisedByFileName;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertiserExtensionsStateService;
import com.intellij.openapi.vfs.VirtualFile;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class PrivatePluginRecommendationProbeTest {
    @Test
    void serviceSetupLinkageFailureReturnsStructuredUnavailableResponse() {
        VirtualFile file = file("/repo/main.go", "main.go", "PLAIN_TEXT");
        when(file.getPath()).thenThrow(new NoClassDefFoundError("file metadata moved"));
        Project project = mock(Project.class);
        Application application = mock(Application.class);

        try (MockedStatic<ApplicationManager> applicationManager = mockStatic(ApplicationManager.class)) {
            applicationManager.when(ApplicationManager::getApplication).thenReturn(application);
            when(application.getService(PluginAdvertiserExtensionsStateService.class))
                .thenThrow(new NoClassDefFoundError("private service moved"));

            Map<String, Object> result = PrivatePluginRecommendationProbe.probe(project, List.of(file));

            assertUnavailableSetupResponse(result, "NoClassDefFoundError");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
            assertNull(files.getFirst().get("path"));
        }
    }

    @Test
    void providerSetupRuntimeFailureReturnsStructuredUnavailableResponse() {
        VirtualFile file = file("/repo/main.go", "main.go", "PLAIN_TEXT");
        Project project = mock(Project.class);
        Application application = mock(Application.class);
        PluginAdvertiserExtensionsStateService service = mock(PluginAdvertiserExtensionsStateService.class);

        try (MockedStatic<ApplicationManager> applicationManager = mockStatic(ApplicationManager.class)) {
            applicationManager.when(ApplicationManager::getApplication).thenReturn(application);
            when(application.getService(PluginAdvertiserExtensionsStateService.class)).thenReturn(service);
            when(service.createExtensionDataProvider(project))
                .thenThrow(new IllegalStateException("provider unavailable"));

            Map<String, Object> result = PrivatePluginRecommendationProbe.probe(project, List.of(file));

            assertUnavailableSetupResponse(result, "IllegalStateException");
        }
    }

    @Test
    void privateResultDecodingFailureBecomesPerFileUnavailable() {
        VirtualFile file = file("/repo/main.go", "main.go", "PLAIN_TEXT");
        PluginAdvertiserExtensionsStateService.ExtensionDataProvider provider =
            mock(PluginAdvertiserExtensionsStateService.ExtensionDataProvider.class);
        @SuppressWarnings("unchecked")
        Set<PluginData> malformedPlugins = mock(Set.class);
        when(malformedPlugins.size()).thenThrow(new NoSuchMethodError("plugin metadata ABI changed"));
        PluginAdvertisedByFileName malformedSuggestion =
            new PluginAdvertisedByFileName("*.go", malformedPlugins);
        when(provider.requestExtensionData$intellij_platform_ide_impl(anyString(), any(FileType.class)))
            .thenReturn(malformedSuggestion);

        Map<String, Object> result = PrivatePluginRecommendationProbe.probeFile(provider, file);

        assertEquals("unavailable", result.get("state"));
        assertEquals("private_api_failure", result.get("reason"));
        assertEquals("NoSuchMethodError", result.get("detail"));
    }

    @Test
    void installationStateLinkageFailureBecomesPerFileUnavailable() {
        VirtualFile file = file("/repo/main.go", "main.go", "PLAIN_TEXT");
        PluginAdvertiserExtensionsStateService.ExtensionDataProvider provider =
            mock(PluginAdvertiserExtensionsStateService.ExtensionDataProvider.class);
        PluginData plugin = new PluginData("org.jetbrains.plugins.go", "Go", false, false);
        PluginAdvertisedByFileName suggestion = new PluginAdvertisedByFileName("*.go", Set.of(plugin));
        when(provider.requestExtensionData$intellij_platform_ide_impl(anyString(), any(FileType.class)))
            .thenReturn(suggestion);

        try (MockedStatic<PluginManagerCore> pluginManager = mockStatic(PluginManagerCore.class)) {
            pluginManager.when(() -> PluginManagerCore.getPlugin(any(PluginId.class)))
                .thenThrow(new NoSuchMethodError("plugin manager ABI changed"));

            Map<String, Object> result = PrivatePluginRecommendationProbe.probeFile(provider, file);

            assertEquals("unavailable", result.get("state"));
            assertEquals("private_api_failure", result.get("reason"));
            assertEquals("NoSuchMethodError", result.get("detail"));
        }
    }

    @Test
    void fatalVmErrorsAreNotConvertedToUnavailableMetadata() {
        VirtualFile file = file("/repo/main.go", "main.go", "PLAIN_TEXT");
        PluginAdvertiserExtensionsStateService.ExtensionDataProvider provider =
            mock(PluginAdvertiserExtensionsStateService.ExtensionDataProvider.class);
        when(provider.requestExtensionData$intellij_platform_ide_impl(anyString(), any(FileType.class)))
            .thenThrow(new OutOfMemoryError("fatal"));

        assertThrows(
            OutOfMemoryError.class,
            () -> PrivatePluginRecommendationProbe.probeFile(provider, file)
        );
    }

    @Test
    void fatalVmErrorsFromSetupAreNotConvertedToUnavailableMetadata() {
        Project project = mock(Project.class);
        Application application = mock(Application.class);

        try (MockedStatic<ApplicationManager> applicationManager = mockStatic(ApplicationManager.class)) {
            applicationManager.when(ApplicationManager::getApplication).thenReturn(application);
            when(application.getService(PluginAdvertiserExtensionsStateService.class))
                .thenThrow(new OutOfMemoryError("fatal"));

            assertThrows(
                OutOfMemoryError.class,
                () -> PrivatePluginRecommendationProbe.probe(project, List.of(file("/repo/main.go", "main.go", "text")))
            );
        }
    }

    @Test
    void cancellationPropagatesAcrossPrivateBoundaries() {
        VirtualFile file = file("/repo/main.go", "main.go", "PLAIN_TEXT");
        Project project = mock(Project.class);
        Application application = mock(Application.class);
        PluginAdvertiserExtensionsStateService.ExtensionDataProvider provider =
            mock(PluginAdvertiserExtensionsStateService.ExtensionDataProvider.class);
        when(provider.requestExtensionData$intellij_platform_ide_impl(anyString(), any(FileType.class)))
            .thenThrow(new IllegalStateException("wrapped cancellation", new ProcessCanceledException()));

        assertThrows(
            ProcessCanceledException.class,
            () -> PrivatePluginRecommendationProbe.probeFile(provider, file)
        );

        try (MockedStatic<ApplicationManager> applicationManager = mockStatic(ApplicationManager.class)) {
            applicationManager.when(ApplicationManager::getApplication).thenReturn(application);
            when(application.getService(PluginAdvertiserExtensionsStateService.class))
                .thenThrow(new ProcessCanceledException());

            assertThrows(
                ProcessCanceledException.class,
                () -> PrivatePluginRecommendationProbe.probe(project, List.of(file))
            );
        }
    }

    @Test
    void fileMetadataFailureCannotProduceAnAvailableRecommendation() {
        VirtualFile file = file("/repo/main.go", "main.go", "PLAIN_TEXT");
        when(file.getPath()).thenThrow(new IllegalStateException("file invalidated"));
        PluginAdvertiserExtensionsStateService.ExtensionDataProvider provider =
            mock(PluginAdvertiserExtensionsStateService.ExtensionDataProvider.class);
        when(provider.requestExtensionData$intellij_platform_ide_impl(anyString(), any(FileType.class)))
            .thenReturn(NoSuggestions.INSTANCE);

        Map<String, Object> result = PrivatePluginRecommendationProbe.probeFile(provider, file);

        assertEquals("unavailable", result.get("state"));
        assertEquals("private_api_failure", result.get("reason"));
        assertEquals("IllegalStateException", result.get("detail"));
        assertNull(result.get("path"));
    }

    @Test
    void aggregatesCoverageAndPrivateProvenance() {
        Map<String, Object> result = PrivatePluginRecommendationFallback.aggregateResponse(
            3,
            List.of(
                Map.of("state", "recommended"),
                Map.of("state", "partial"),
                Map.of("state", "unavailable")
            )
        );
        Map<String, Object> unavailable = PrivatePluginRecommendationFallback.aggregateResponse(
            2,
            List.of(Map.of("state", "unavailable"), Map.of("state", "unavailable"))
        );
        Map<String, Object> empty = PrivatePluginRecommendationFallback.aggregateResponse(0, List.of());

        assertEquals("ok", result.get("status"));
        assertEquals(1, result.get("schema_version"));
        assertEquals("jetbrains_private_plugin_advertiser_262", result.get("source"));
        assertEquals("private_internal", result.get("api_status"));
        assertEquals("262", result.get("platform_line"));
        assertEquals(true, result.get("read_only"));
        assertEquals("partial", result.get("coverage"));
        assertEquals(3, result.get("files_requested"));
        assertEquals("unavailable", unavailable.get("coverage"));
        assertEquals("unavailable", empty.get("coverage"));
    }

    @Test
    void unsupportedPrivateResultRemainsExplicitPartialCoverage() {
        VirtualFile file = file("/repo/main.go", "main.go", "PLAIN_TEXT");
        PluginAdvertisedByFileContent unsupported = mock(PluginAdvertisedByFileContent.class);

        Map<String, Object> result =
            PrivatePluginRecommendationProbe.describeSuggestion(file, unsupported);

        assertEquals("partial", result.get("state"));
        assertEquals("unsupported_private_result_type", result.get("reason"));
        assertEquals(PluginAdvertisedByFileContent.class.getName(), result.get("private_result_type"));
    }

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

    @SuppressWarnings("unchecked")
    private static void assertUnavailableSetupResponse(Map<String, Object> result, String detail) {
        assertEquals("ok", result.get("status"));
        assertEquals("jetbrains_private_plugin_advertiser_262", result.get("source"));
        assertEquals("private_internal", result.get("api_status"));
        assertEquals("unavailable", result.get("coverage"));
        assertEquals("private_api_setup_failure", result.get("reason"));
        assertEquals(detail, result.get("detail"));
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        assertEquals(1, files.size());
        assertEquals("unavailable", files.getFirst().get("state"));
        assertEquals("private_api_setup_failure", files.getFirst().get("reason"));
        assertEquals(detail, files.getFirst().get("detail"));
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
