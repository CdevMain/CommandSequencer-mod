package csequencer.client;

import com.google.gson.JsonObject;

/** Metadata of the most recently downloaded project (ownership/permissions as told by the server). */
public final class ProjectSession {
    private ProjectSession() {}

    public static JsonObject lastMeta;
    public static boolean lastCanEdit;
}
