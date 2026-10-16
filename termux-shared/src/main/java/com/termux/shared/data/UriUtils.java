package com.termux.shared.data;

import android.content.ContentResolver;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class UriUtils {

    private UriUtils() { }

    /**
     * Recover a file path from a URI, including an unescaped '#'. Intents created by commands
     * such as {@code am -d} parse the part after '#' as a fragment. URIs built with
     * {@link Uri.Builder#path(String)} encode it in the path instead and have no fragment.
     */
    @Nullable
    public static String getUriFilePathWithFragment(@Nullable Uri uri) {
        if (uri == null) return null;
        String path = uri.getPath();
        if (DataUtils.isNullOrEmpty(path)) return null;
        String fragment = uri.getFragment();
        return path + (fragment == null ? "" : "#" + fragment);
    }

    /** Return the last component of the URI's file path when no display name is available. */
    @Nullable
    public static String getUriFileBasename(@Nullable Uri uri, boolean withFragment) {
        if (uri == null) return null;
        String path = withFragment ? getUriFilePathWithFragment(uri) : uri.getPath();
        if (DataUtils.isNullOrEmpty(path)) return null;
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Build a content URI without interpreting reserved characters in the file path. */
    @NonNull
    public static Uri getContentUri(@NonNull String authority, @NonNull String path) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).path(path).build();
    }
}
