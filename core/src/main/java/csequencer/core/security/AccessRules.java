package csequencer.core.security;

import csequencer.core.project.ProjectMeta;

/**
 * Ownership and sharing rules. Inputs are the server's own view of the viewer's permissions
 * ({@code perms}), never client claims.
 * <ul>
 *   <li>View: admin, owner/editor, or visibility SHARED/PUBLIC.</li>
 *   <li>Edit/save: EDIT+UPLOAD permission and (admin or owner/editor). New projects: UPLOAD.</li>
 *   <li>Run: RUN permission and (admin, owner/editor, or visibility SHARED/PUBLIC).</li>
 *   <li>Delete / change sharing: admin or owner.</li>
 * </ul>
 */
public final class AccessRules {
    private AccessRules() {}

    public interface Perms {
        boolean has(Permission p);
    }

    public static boolean canView(ProjectMeta m, String uuid, Perms perms) {
        if (perms.has(Permission.ADMIN)) return true;
        if (m.isEditor(uuid)) return true;
        return m.visibility != ProjectMeta.Visibility.PRIVATE;
    }

    public static boolean canEdit(ProjectMeta m, String uuid, Perms perms) {
        if (!perms.has(Permission.EDIT) || !perms.has(Permission.UPLOAD)) return perms.has(Permission.ADMIN);
        return perms.has(Permission.ADMIN) || m.isEditor(uuid);
    }

    public static boolean canCreate(Perms perms) {
        return perms.has(Permission.ADMIN) || (perms.has(Permission.UPLOAD) && perms.has(Permission.EDIT));
    }

    public static boolean canRun(ProjectMeta m, String uuid, Perms perms) {
        if (perms.has(Permission.ADMIN)) return true;
        if (!perms.has(Permission.RUN)) return false;
        return m.isEditor(uuid) || m.visibility != ProjectMeta.Visibility.PRIVATE;
    }

    public static boolean canDelete(ProjectMeta m, String uuid, Perms perms) {
        return perms.has(Permission.ADMIN) || (m.isOwner(uuid) && perms.has(Permission.UPLOAD));
    }
}
