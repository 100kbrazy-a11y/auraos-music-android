package com.auraos.music;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Hands what you dragged out of AuraOS Music to the app you dropped it on: content://<app>.dragout/<token>/<name>.
 * The other app gets the file through a pipe straight away, so it never waits on us; the data flows as soon as it
 * exists (a song at once, a music pack once it's been named and made). Read-only, and only for that one drop.
 */
public class DragOutProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private static DragOutPlugin.Entry entry(Uri uri) {
        List<String> seg = uri.getPathSegments();
        return seg.isEmpty() ? null : DragOutPlugin.entries.get(seg.get(0));
    }

    private static void begin(DragOutPlugin.Entry e) {
        DragOutPlugin p = DragOutPlugin.instance;
        if (p != null) p.begin(e);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        DragOutPlugin.Entry e = entry(uri);
        if (e == null) return null;
        begin(e);
        String[] cols = projection != null ? projection : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = e.name;
            else if (OpenableColumns.SIZE.equals(cols[i])) {
                long size = e.file != null && e.file.exists() ? e.file.length() : e.size;
                row[i] = size > 0 ? size : null;
            }
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        DragOutPlugin.Entry e = entry(uri);
        return e == null ? null : e.mime;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        final DragOutPlugin.Entry e = entry(uri);
        if (e == null) throw new FileNotFoundException("That drag has expired");
        begin(e);
        // already there (a song that finished writing): hand over the real file, seekable
        if (e.ready.getCount() == 0 && e.error == null && e.file != null && e.file.exists())
            return ParcelFileDescriptor.open(e.file, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            new Thread(() -> {
                ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                try {
                    boolean ok = e.ready.await(20, TimeUnit.MINUTES);
                    if (!ok || e.error != null || e.file == null || !e.file.exists()) {
                        pipe[1].closeWithError(e.error != null ? e.error : "not ready");
                        return;
                    }
                    try (InputStream in = new FileInputStream(e.file)) { copy(in, out); }
                    out.close();
                } catch (Exception ex) {
                    try { pipe[1].closeWithError(String.valueOf(ex.getMessage())); } catch (IOException ignored) {}
                }
            }, "dragout-" + e.token).start();
            return pipe[0];
        } catch (IOException ex) {
            throw new FileNotFoundException(ex.getMessage());
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
