package csequencer.core.engine;

import csequencer.core.model.Project;
import csequencer.core.project.ProjectMeta;

import java.util.List;

/** Read access to stored projects for the engine (chaining, triggers, recovery). */
public interface ProjectCatalog {
    record Entry(Project project, ProjectMeta meta) {}

    /** Finds a project by id or (case-insensitive) name. */
    Entry find(String idOrName);

    /** All stored projects (used for triggers and the monitor). */
    List<Entry> all();

    ProjectCatalog EMPTY = new ProjectCatalog() {
        @Override public Entry find(String idOrName) { return null; }
        @Override public List<Entry> all() { return List.of(); }
    };
}
