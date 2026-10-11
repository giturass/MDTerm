package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Private application storage for saved locations and their login settings. */
public final class TerminalBookmarkStore {
    private final SharedPreferences preferences;

    public TerminalBookmarkStore(Context context) {
        preferences = context.getSharedPreferences("terminal_bookmarks", Context.MODE_PRIVATE);
    }

    public List<TerminalBookmark> getAll() {
        List<TerminalBookmark> bookmarks = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(preferences.getString("bookmarks", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                try {
                    JSONObject item = saved.getJSONObject(i);
                    String kind = item.getString("kind");
                    String path = item.getString("path");
                    if (!("local".equals(kind) || "proot".equals(kind) || "ssh".equals(kind))
                        || !path.startsWith("/")) continue;
                    List<String> sshArgs = new ArrayList<>();
                    JSONArray args = item.optJSONArray("sshArgs");
                    if (args != null) {
                        for (int j = 0; j < args.length(); j++) sshArgs.add(args.getString(j));
                    }
                    JSONObject proot = item.optJSONObject("prootLaunch");
                    if (item.has("prootLaunch") && proot == null)
                        throw new JSONException("Invalid PRoot login settings");
                    BookmarkProotLaunch prootLaunch = proot == null ? null : new BookmarkProotLaunch(
                        readStrings(proot.getJSONArray("arguments")), readStrings(proot.getJSONArray("environment")),
                        proot.getString("workingDirectory"));
                    bookmarks.add(new TerminalBookmark(item.getString("id"), item.getString("name"),
                        kind, item.optString("distro", ""), sshArgs, path, prootLaunch));
                } catch (JSONException | IllegalArgumentException ignored) {
                    // Preserve other entries if an individual saved item is damaged.
                }
            }
        } catch (JSONException ignored) {
            // Treat an unreadable preference as an empty collection.
        }
        return bookmarks;
    }

    public void add(TerminalBookmark bookmark) {
        List<TerminalBookmark> bookmarks = getAll();
        bookmarks.removeIf(item -> item.id.equals(bookmark.id));
        bookmarks.add(bookmark);
        save(bookmarks);
    }

    private static List<String> readStrings(JSONArray array) throws JSONException {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        return values;
    }

    public void rename(String id, String name) {
        List<TerminalBookmark> bookmarks = getAll();
        for (int i = 0; i < bookmarks.size(); i++) {
            if (bookmarks.get(i).id.equals(id)) bookmarks.set(i, bookmarks.get(i).withName(name));
        }
        save(bookmarks);
    }

    public void delete(String id) {
        List<TerminalBookmark> bookmarks = getAll();
        bookmarks.removeIf(item -> item.id.equals(id));
        save(bookmarks);
    }

    private void save(List<TerminalBookmark> bookmarks) {
        JSONArray saved = new JSONArray();
        try {
            for (TerminalBookmark bookmark : bookmarks) {
                JSONObject item = new JSONObject();
                item.put("id", bookmark.id);
                item.put("name", bookmark.name);
                item.put("kind", bookmark.kind);
                item.put("distro", bookmark.distro);
                item.put("sshArgs", new JSONArray(bookmark.sshArgs));
                item.put("path", bookmark.path);
                if (bookmark.prootLaunch != null) {
                    JSONObject proot = new JSONObject();
                    proot.put("arguments", new JSONArray(bookmark.prootLaunch.arguments));
                    proot.put("environment", new JSONArray(bookmark.prootLaunch.environment));
                    proot.put("workingDirectory", bookmark.prootLaunch.workingDirectory);
                    item.put("prootLaunch", proot);
                }
                saved.put(item);
            }
        } catch (JSONException e) {
            throw new IllegalStateException("Cannot serialize terminal bookmarks", e);
        }
        preferences.edit().putString("bookmarks", saved.toString()).apply();
    }
}
