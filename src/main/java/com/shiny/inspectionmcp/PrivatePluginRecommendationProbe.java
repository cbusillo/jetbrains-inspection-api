package com.shiny.inspectionmcp;

import com.intellij.ide.plugins.advertiser.PluginData;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.NoSuggestions;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertisedByFileName;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertiserExtensionsStateService;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertiserSuggestion;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class PrivatePluginRecommendationProbe {
    private PrivatePluginRecommendationProbe() {
    }

    static Map<String, Object> probe(Project project, List<VirtualFile> files) {
        PluginAdvertiserExtensionsStateService.ExtensionDataProvider provider;
        try {
            PluginAdvertiserExtensionsStateService service =
                PluginAdvertiserExtensionsStateService.Companion.getInstance();
            provider = service.createExtensionDataProvider(project);
        } catch (LinkageError error) {
            return PrivatePluginRecommendationFallback.setupFailureResponse(files, error);
        } catch (RuntimeException error) {
            return PrivatePluginRecommendationFallback.setupFailureResponse(files, error);
        }

        List<Map<String, Object>> fileResults = new ArrayList<>(files.size());
        for (VirtualFile file : files) {
            fileResults.add(probeFile(provider, file));
        }
        return PrivatePluginRecommendationFallback.aggregateResponse(files.size(), fileResults);
    }

    static Map<String, Object> probeFile(
        PluginAdvertiserExtensionsStateService.ExtensionDataProvider provider,
        VirtualFile file
    ) {
        try {
            PluginAdvertiserSuggestion suggestion = provider.requestExtensionData$intellij_platform_ide_impl(
                file.getName(),
                file.getFileType()
            );
            return describeSuggestion(file, suggestion);
        } catch (LinkageError error) {
            return PrivatePluginRecommendationFallback.unavailableFile(file, "private_api_failure", error);
        } catch (RuntimeException error) {
            return PrivatePluginRecommendationFallback.unavailableFile(file, "private_api_failure", error);
        }
    }

    static Map<String, Object> describeSuggestion(VirtualFile file, PluginAdvertiserSuggestion suggestion) {
        return describeSuggestion(file, suggestion, PrivatePluginRecommendationProbe::installationState);
    }

    static Map<String, Object> describeSuggestion(
        VirtualFile file,
        PluginAdvertiserSuggestion suggestion,
        Function<String, String> installationStateResolver
    ) {
        Map<String, Object> result = baseFile(file);
        if (suggestion == null) {
            result.put("state", "unavailable");
            result.put("reason", "recommendation_cache_unavailable");
            return result;
        }
        if (suggestion == NoSuggestions.INSTANCE) {
            result.put("state", "none");
            result.put("reason", "no_recommendation");
            return result;
        }
        if (suggestion instanceof PluginAdvertisedByFileName byFileName) {
            List<Map<String, Object>> allPlugins = pluginMetadata(
                byFileName.plugins,
                installationStateResolver
            );
            if (allPlugins.isEmpty()) {
                result.put("state", "unavailable");
                result.put("reason", "recommendation_plugin_metadata_unavailable");
                return result;
            }
            List<Map<String, Object>> actionablePlugins = allPlugins.stream()
                .filter(plugin -> !"enabled".equals(plugin.get("installation_state")))
                .toList();
            if (actionablePlugins.isEmpty()) {
                result.put("state", "none");
                result.put("reason", "recommended_plugin_already_enabled");
                result.put("plugins", allPlugins);
                return result;
            }
            result.put("state", "recommended");
            result.put("trigger", Map.of(
                "kind", "file_name_or_extension",
                "value", byFileName.extensionOrFileName
            ));
            result.put("plugins", actionablePlugins);
            return result;
        }
        result.put("state", "partial");
        result.put("reason", "unsupported_private_result_type");
        result.put("private_result_type", suggestion.getClass().getName());
        return result;
    }

    private static Map<String, Object> baseFile(VirtualFile file) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", file.getPath());
        result.put("name", file.getName());
        result.put("file_type", file.getFileType().getName());
        return result;
    }

    private static List<Map<String, Object>> pluginMetadata(
        Set<PluginData> plugins,
        Function<String, String> installationStateResolver
    ) {
        List<Map<String, Object>> metadata = new ArrayList<>(plugins.size());
        for (PluginData plugin : plugins) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plugin_id", plugin.pluginIdString);
            if (plugin.nullablePluginName != null && !plugin.nullablePluginName.isBlank()) {
                row.put("plugin_name", plugin.nullablePluginName);
            }
            row.put("installation_state", installationStateResolver.apply(plugin.pluginIdString));
            metadata.add(row);
        }
        metadata.sort(Comparator.comparing(row -> (String) row.get("plugin_id")));
        return metadata;
    }

    private static String installationState(String pluginIdString) {
        PluginId pluginId = PluginId.getId(pluginIdString);
        IdeaPluginDescriptor installedPlugin = PluginManagerCore.getPlugin(pluginId);
        if (installedPlugin == null) {
            return "absent";
        }
        return PluginManagerCore.isLoaded(installedPlugin) ? "enabled" : "disabled";
    }
}
