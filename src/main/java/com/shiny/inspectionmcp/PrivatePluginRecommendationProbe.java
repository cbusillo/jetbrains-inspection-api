package com.shiny.inspectionmcp;

import com.intellij.ide.plugins.advertiser.PluginData;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.progress.ProcessCanceledException;
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
import java.util.function.Supplier;

final class PrivatePluginRecommendationProbe {
    private static final int SCHEMA_VERSION = 1;
    private static final String SOURCE = "jetbrains_private_plugin_advertiser_262";

    private PrivatePluginRecommendationProbe() {
    }

    static Map<String, Object> probe(Project project, List<VirtualFile> files) {
        PluginAdvertiserExtensionsStateService.ExtensionDataProvider provider;
        try {
            PluginAdvertiserExtensionsStateService service =
                PluginAdvertiserExtensionsStateService.Companion.getInstance();
            provider = service.createExtensionDataProvider(project);
        } catch (LinkageError error) {
            return setupFailureResponse(files, error);
        } catch (RuntimeException error) {
            rethrowIfCanceled(error);
            return setupFailureResponse(files, error);
        }

        List<Map<String, Object>> fileResults = new ArrayList<>(files.size());
        for (VirtualFile file : files) {
            fileResults.add(probeFile(provider, file));
        }
        return aggregateResponse(files.size(), fileResults);
    }

    static Map<String, Object> aggregateResponse(
        int filesRequested,
        List<Map<String, Object>> fileResults
    ) {
        long unavailableCount = fileResults.stream()
            .filter(result -> "unavailable".equals(result.get("state")))
            .count();
        long partialCount = fileResults.stream()
            .filter(result -> "partial".equals(result.get("state")))
            .count();
        String coverage = fileResults.isEmpty()
            ? "unavailable"
            : unavailableCount == 0
                ? partialCount == 0 ? "available" : "partial"
                : unavailableCount == fileResults.size() ? "unavailable" : "partial";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("schema_version", SCHEMA_VERSION);
        response.put("source", SOURCE);
        response.put("api_status", "private_internal");
        response.put("platform_line", "262");
        response.put("read_only", true);
        response.put("coverage", coverage);
        response.put("files_requested", filesRequested);
        response.put("files", fileResults);
        return response;
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
            return unavailableFile(file, "private_api_failure", failureDetail(error));
        } catch (RuntimeException error) {
            rethrowIfCanceled(error);
            return unavailableFile(file, "private_api_failure", failureDetail(error));
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

    private static Map<String, Object> unavailableFile(VirtualFile file, String reason, String detail) {
        Map<String, Object> result = safeBaseFile(file);
        result.put("state", "unavailable");
        result.put("reason", reason);
        result.put("detail", detail);
        return result;
    }

    private static Map<String, Object> setupFailureResponse(List<VirtualFile> files, Throwable error) {
        String detail = failureDetail(error);
        List<Map<String, Object>> fileResults = files.stream()
            .map(file -> unavailableFile(file, "private_api_setup_failure", detail))
            .toList();
        Map<String, Object> response = aggregateResponse(files.size(), fileResults);
        response.put("reason", "private_api_setup_failure");
        response.put("detail", detail);
        return response;
    }

    private static String failureDetail(Throwable error) {
        String simpleName = error.getClass().getSimpleName();
        return simpleName.isBlank() ? error.getClass().getName() : simpleName;
    }

    private static Map<String, Object> baseFile(VirtualFile file) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", file.getPath());
        result.put("name", file.getName());
        result.put("file_type", file.getFileType().getName());
        return result;
    }

    private static Map<String, Object> safeBaseFile(VirtualFile file) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", safeFileMetadata(file::getPath));
        result.put("name", safeFileMetadata(file::getName));
        result.put("file_type", safeFileMetadata(() -> file.getFileType().getName()));
        return result;
    }

    private static String safeFileMetadata(Supplier<String> metadataReader) {
        try {
            return metadataReader.get();
        } catch (LinkageError error) {
            return null;
        } catch (RuntimeException error) {
            rethrowIfCanceled(error);
            return null;
        }
    }

    private static void rethrowIfCanceled(RuntimeException error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ProcessCanceledException canceled) {
                throw canceled;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return;
            }
            current = cause;
        }
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
