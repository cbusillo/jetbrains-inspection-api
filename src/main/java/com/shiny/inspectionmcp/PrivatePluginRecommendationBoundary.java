package com.shiny.inspectionmcp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.List;
import java.util.Map;

final class PrivatePluginRecommendationBoundary {
    private PrivatePluginRecommendationBoundary() {
    }

    static Map<String, Object> probe(Project project, List<VirtualFile> files) {
        try {
            return PrivatePluginRecommendationProbe.probe(project, files);
        } catch (LinkageError error) {
            return PrivatePluginRecommendationFallback.setupFailureResponse(files, error);
        } catch (RuntimeException error) {
            PrivatePluginRecommendationFallback.rethrowControlFlow(error);
            throw error;
        }
    }
}
