package com.deepseek.dsh.android;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class SafStore {

    private static final String PREFS_NAME = "dsh_storage";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_WORKSPACE_PATH = "workspace_path";
    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";

    private final Context context;
    private final SharedPreferences preferences;

    public SafStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveSelection(Uri treeUri, int grantedFlags) throws IOException {
        if (treeUri == null) {
            throw new IOException("SAF tree URI is missing");
        }
        int required = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if ((grantedFlags & required) != required) {
            throw new IOException("SAF tree grant lacks read/write flags");
        }
        File mapped = mapTreeUri(treeUri);
        try {
            context.getContentResolver().takePersistableUriPermission(treeUri, required);
        } catch (SecurityException e) {
            throw new IOException("SAF tree permission persistence failed", e);
        }
        if (!preferences.edit()
                .putString(KEY_TREE_URI, treeUri.toString())
                .putString(KEY_WORKSPACE_PATH, mapped.getAbsolutePath())
                .commit()) {
            throw new IOException("SAF selection persistence failed");
        }
    }

    public Uri getTreeUri() {
        String value = preferences.getString(KEY_TREE_URI, null);
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    public File getWorkspaceRoot() {
        String path = preferences.getString(KEY_WORKSPACE_PATH, null);
        if (path == null || path.isEmpty() || getTreeUri() == null) {
            return null;
        }
        return new File(path);
    }

    public String getSelectionLabel() {
        Uri treeUri = getTreeUri();
        if (treeUri == null) {
            return "应用私有工作区";
        }
        final String id;
        try {
            id = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException e) {
            return "已授权存储目录";
        }
        int colon = id.indexOf(':');
        if (colon < 0) {
            return id;
        }
        String volume = id.substring(0, colon);
        String relative = id.substring(colon + 1);
        if (volume.equals("primary")) {
            return relative.isEmpty() ? "内部存储根目录" : "内部存储/" + relative;
        }
        return relative.isEmpty() ? volume : volume + "/" + relative;
    }

    public String verifySelection() throws IOException {
        Uri treeUri = getTreeUri();
        File root = getWorkspaceRoot();
        if (treeUri == null || root == null) {
            throw new IOException("SAF storage selection is missing");
        }
        boolean permitted = false;
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(treeUri)
                    && permission.isReadPermission() && permission.isWritePermission()) {
                permitted = true;
                break;
            }
        }
        if (!permitted) {
            throw new IOException("SAF persisted read/write permission is missing");
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            throw new IOException("All-files access is required for node POSIX paths");
        }
        if (Build.VERSION.SDK_INT == 29
                && (context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new IOException("External storage permission is missing");
        }
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Cannot create selected workspace root");
        }
        if (!root.isDirectory()) {
            throw new IOException("Selected workspace root is not a directory");
        }
        File probe = File.createTempFile(".dsh-write-", ".tmp", root);
        try (FileOutputStream output = new FileOutputStream(probe)) {
            output.write(1);
        } finally {
            if (probe.exists()) {
                probe.delete();
            }
        }
        return root.getCanonicalPath();
    }

    public static File mapTreeUri(Uri treeUri) throws IOException {
        if (!EXTERNAL_STORAGE_AUTHORITY.equals(treeUri.getAuthority())) {
            throw new IOException("Selected SAF provider does not expose a shared-storage path");
        }
        final String id;
        try {
            id = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid SAF tree URI", e);
        }
        int colon = id.indexOf(':');
        if (colon <= 0) {
            throw new IOException("Invalid external-storage document id");
        }
        String volume = id.substring(0, colon);
        String relative = id.substring(colon + 1);
        if (volume.isEmpty() || relative.indexOf('\0') >= 0 || relative.indexOf('\\') >= 0) {
            throw new IOException("Invalid external-storage path");
        }
        String[] segments = relative.split("/", -1);
        for (String segment : segments) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IOException("External-storage path traversal is invalid");
            }
        }
        File base = volume.equals("primary")
                ? Environment.getExternalStorageDirectory()
                : new File("/storage", volume);
        File mapped = relative.isEmpty() ? base : new File(base, relative);
        File canonicalBase = base.getCanonicalFile();
        File canonicalMapped = mapped.getCanonicalFile();
        String boundary = canonicalBase.getPath().endsWith(File.separator)
                ? canonicalBase.getPath() : canonicalBase.getPath() + File.separator;
        if (!canonicalMapped.equals(canonicalBase)
                && !canonicalMapped.getPath().startsWith(boundary)) {
            throw new IOException("Mapped path escapes the selected storage volume");
        }
        return canonicalMapped;
    }
}
