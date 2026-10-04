package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.manager.planner.WorkSource;

/**
 * Live part of a running project: a {@link WorkSource} for the planner plus progress for the panel. Created on
 * start / resume, dropped on pause / stop. Report completion with {@link ProjectService#done(Project)} and fatal
 * problems with {@link ProjectService#failed(Project, String)}.
 */
public interface ProjectRuntime extends WorkSource {
    /** Called once after creation (start or resume). {@code restart}: started again after done / failed / stop. */
    void start(boolean restart);

    /** Called when the project stops running (pause, stop, delete, done, failed). */
    void stop();

    /** Kind-specific view merged into the project resource: {@code progress}, {@code bom}, {@code sectors}, ... */
    JsonObject view(boolean full);

    /** State to keep across manager restarts (written to {@link Project#state}). */
    JsonObject persist();
}
