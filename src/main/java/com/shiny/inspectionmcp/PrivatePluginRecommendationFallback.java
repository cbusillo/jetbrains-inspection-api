package com.shiny.inspectionmcp;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PrivatePluginRecommendationFallback {
    private static final int SCHEMA_VERSION = 1;
    private static final String SOURCE = "jetbrains_private_plugin_advertiser_262";

    private PrivatePluginRecommendationFallback() {
    }

    static Map<String, Object> setupFailureResponse(List<VirtualFile> files, Throwable error) {
        rethrowControlFlow(error);
        String detail = failureDetail(error);
        List<Map<String, Object>> fileResults = new ArrayList<>(files.size());
        for (VirtualFile file : files) {
            fileResults.add(unavailableFile(file, "private_api_setup_failure", detail));
        }

        Map<String, Object> response = aggregateResponse(files.size(), fileResults);
        response.put("reason", "private_api_setup_failure");
        response.put("detail", detail);
        return response;
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

    static void rethrowControlFlow(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && visited.add(current)) {
            if (current instanceof ProcessCanceledException canceled) {
                throw canceled;
            }
            if (current instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            current = current.getCause();
        }
    }

    static Map<String, Object> unavailableFile(VirtualFile file, String reason, Throwable error) {
        rethrowControlFlow(error);
        return unavailableFile(file, reason, failureDetail(error));
    }

    private static Map<String, Object> unavailableFile(VirtualFile file, String reason, String detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", safeFilePath(file));
        result.put("name", safeFileName(file));
        result.put("file_type", safeFileType(file));
        result.put("state", "unavailable");
        result.put("reason", reason);
        result.put("detail", detail);
        return result;
    }

    private static String safeFilePath(VirtualFile file) {
        try {
            return file.getPath();
        } catch (LinkageError error) {
            rethrowControlFlow(error);
            return null;
        } catch (RuntimeException error) {
            rethrowControlFlow(error);
            return null;
        }
    }

    private static String safeFileName(VirtualFile file) {
        try {
            return file.getName();
        } catch (LinkageError error) {
            rethrowControlFlow(error);
            return null;
        } catch (RuntimeException error) {
            rethrowControlFlow(error);
            return null;
        }
    }

    private static String safeFileType(VirtualFile file) {
        try {
            return file.getFileType().getName();
        } catch (LinkageError error) {
            rethrowControlFlow(error);
            return null;
        } catch (RuntimeException error) {
            rethrowControlFlow(error);
            return null;
        }
    }

    private static String failureDetail(Throwable error) {
        String simpleName = error.getClass().getSimpleName();
        return simpleName.isBlank() ? error.getClass().getName() : simpleName;
    }
}
