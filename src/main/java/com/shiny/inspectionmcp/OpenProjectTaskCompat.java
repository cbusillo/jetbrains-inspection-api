package com.shiny.inspectionmcp;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.ide.impl.OpenProjectTaskKt;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

final class OpenProjectTaskCompat {
    private OpenProjectTaskCompat() {
    }

    static OpenProjectTask build(Path openPath, Function1<? super Project, Unit> onBeforeInit) {
        return OpenProjectTaskKt.OpenProjectTask(builder -> {
            builder.setForceOpenInNewFrame(true);
            Path fileName = openPath.getFileName();
            builder.setProjectName(fileName == null ? openPath.toString() : fileName.toString());
            builder.setBeforeInit(onBeforeInit);
            return Unit.INSTANCE;
        });
    }
}
