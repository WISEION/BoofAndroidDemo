package org.boofcv.mysicgramm;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Saves captured stills and videos so they show up in the device gallery.
 *
 * <p>Photos are written through {@link MediaStore} on API 29+ (scoped storage) and to the public
 * Pictures directory on older versions. Videos are recorded to a file by {@link VideoRecorder} and
 * then registered here.</p>
 */
public class MediaSaver {

    public static final String ALBUM = "Mysicgramm";

    /** Directory used for video files (also used as a legacy fallback for photos). */
    public static File albumDir(Context context, String type) {
        File base;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            base = new File(Environment.getExternalStoragePublicDirectory(type), ALBUM);
        } else {
            base = new File(context.getFilesDir(), ALBUM);
        }
        if (!base.exists()) {
            //noinspection ResultOfMethodCallIgnored
            base.mkdirs();
        }
        return base;
    }

    /**
     * Saves a bitmap as a JPEG. Returns a human readable location for a toast, or null on failure.
     */
    public static String savePhoto(Context context, Bitmap bitmap, String displayName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return savePhotoMediaStore(context, bitmap, displayName);
            }
            return savePhotoLegacy(context, bitmap, displayName);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String savePhotoMediaStore(Context context, Bitmap bitmap, String displayName) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + ALBUM);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = resolver.insert(collection, values);
        if (item == null)
            throw new IllegalStateException("MediaStore insert failed");

        try (OutputStream out = resolver.openOutputStream(item)) {
            if (out == null)
                throw new IllegalStateException("Unable to open output stream");
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
        }

        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(item, values, null, null);

        return "Pictures/" + ALBUM;
    }

    private static String savePhotoLegacy(Context context, Bitmap bitmap, String displayName) throws Exception {
        File dir = albumDir(context, Environment.DIRECTORY_PICTURES);
        File file = new File(dir, displayName + ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
        }
        scan(context, file, "image/jpeg");
        return file.getParent();
    }

    /** Creates a fresh, app-private file for {@link VideoRecorder} to write an mp4 into. */
    public static File newVideoFile(Context context, String displayName) {
        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), ALBUM);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, displayName + ".mp4");
    }

    /** Makes a recorded video file visible in the gallery. */
    public static void registerVideo(Context context, File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + File.separator + ALBUM);
                values.put(MediaStore.Video.Media.IS_PENDING, 1);

                Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri item = resolver.insert(collection, values);
                if (item == null)
                    return;
                try (OutputStream out = resolver.openOutputStream(item);
                     java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    if (out != null) {
                        byte[] buffer = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buffer)) > 0)
                            out.write(buffer, 0, n);
                    }
                }
                values.clear();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                resolver.update(item, values, null, null);
                //noinspection ResultOfMethodCallIgnored
                file.delete(); // copied into MediaStore, temp no longer needed
            } catch (Exception e) {
                e.printStackTrace();
                scan(context, file, "video/mp4");
            }
        } else {
            scan(context, file, "video/mp4");
        }
    }

    private static void scan(Context context, File file, String mime) {
        MediaScannerConnection.scanFile(context,
                new String[]{file.getAbsolutePath()}, new String[]{mime}, null);
    }
}
