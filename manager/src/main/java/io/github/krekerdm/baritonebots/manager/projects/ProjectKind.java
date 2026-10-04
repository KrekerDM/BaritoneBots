package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonObject;

/**
 * A project kind (SPEC §5.7: build, gather, clear, farm, ranch, sort, smelt). Register new kinds in
 * {@link ProjectService#ProjectService}; the catalog's {@code projectKinds} entry describes the config form.
 */
public interface ProjectKind {
    /** {@code build}, {@code gather}, ... */
    String kind();

    /**
     * Validates and normalises the kind-specific {@code config} of a create / replace request.
     *
     * @throws io.github.krekerdm.baritonebots.manager.config.ValidationException with paths like {@code config.origin}
     */
    JsonObject normalizeConfig(JsonObject config, String serverId);

    /** The live part of a project that is about to run (state comes from {@link Project#state}). */
    ProjectRuntime runtime(Project p, ProjectService service);
}
