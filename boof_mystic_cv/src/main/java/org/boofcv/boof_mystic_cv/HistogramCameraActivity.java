package org.boofcv.boof_mystic_cv;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import boofcv.alg.color.ColorFormat;
import boofcv.alg.enhance.EnhanceImageOps;
import boofcv.alg.misc.ImageStatistics;
import boofcv.android.ConvertBitmap;
import boofcv.android.camera2.CameraID;
import boofcv.android.camera2.VisualizeCamera2Activity;
import boofcv.core.image.ConvertImage;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageBase;
import boofcv.struct.image.ImageType;
import boofcv.struct.image.Planar;
import org.ddogleg.struct.DogArray_I32;
import org.ddogleg.struct.DogArray_I8;
import pabeles.concurrency.GrowArray;

/**
 * Boof Mystic CV — a focused camera app that shows live histogram enhancement using BoofCV.
 *
 * <p>Two enhancement modes are offered, toggled by a single button:</p>
 * <ul>
 *     <li><b>Global</b> histogram equalization (one transform over the whole image), and</li>
 *     <li><b>Local</b> (adaptive) histogram equalization (a sliding window per pixel).</li>
 * </ul>
 *
 * <p>Gestures on the live preview:</p>
 * <ul>
 *     <li>One-finger <b>horizontal</b> swipe — change resolution (right = higher, left = lower).</li>
 *     <li>One-finger <b>vertical</b> swipe — cycle through the device cameras.</li>
 *     <li><b>Double-tap</b> — toggle the live histogram overlay.</li>
 *     <li><b>Long-press &amp; hold</b> — momentarily show the raw (before) image for comparison.</li>
 * </ul>
 *
 * <p>The shutter button takes a photo on tap and records video while held. Captures are saved in
 * both the enhanced and raw versions.</p>
 */
public class HistogramCameraActivity extends VisualizeCamera2Activity {

    private enum Mode { GLOBAL, LOCAL }

    // Candidate capture resolutions, in pixels, selected by the horizontal swipe.
    private static final int[] BUCKET = {320 * 240, 640 * 480, 1024 * 768, 1920 * 1080};
    private static final String[] BUCKET_NAME = {"LOW", "MEDIUM", "HIGH", "MAX"};
    private static final int MIN_PIXELS = 320 * 240;

    private static final int REQUEST_PERMISSIONS = 1;

    // ------------------------------------------------------------------ settings / state
    private Mode mode = Mode.GLOBAL;
    private boolean colorMode = false;
    private int radius = 50;      // local window radius, 5..100
    private int histBins = 256;   // histogram length for local equalization, 16..256
    private int downscale = 1;    // processing downscale factor: 1, 2 or 4
    private int resIndex = 1;     // index into BUCKET, default MEDIUM
    private boolean showInfo = true;
    private boolean showOverlay = false;

    private volatile boolean showRaw = false; // long-press before/after compare

    // ------------------------------------------------------------------ processing buffers
    private final int[] histogram = new int[256];
    private final int[] transform = new int[256];
    private final int[] displayHistogram = new int[256];
    private final int[] binLut = new int[256];
    private int binLutFor = -1; // histBins value the LUT was last built for
    private final GrayU8 enhancedGray = new GrayU8(1, 1);
    private final Planar<GrayU8> enhancedColor = new Planar<>(GrayU8.class, 1, 1, 3);
    private final GrayU8 gray = new GrayU8(1, 1);
    private final GrowArray<DogArray_I32> work = new GrowArray<>(DogArray_I32::new);
    private volatile ImageBase enhanced;

    private double fps = 0;
    private long lastFrameNanos = 0;

    // ------------------------------------------------------------------ camera selection
    private String[] cameraIds = new String[0];
    private int cameraIndex = 0;

    // ------------------------------------------------------------------ capture
    private volatile boolean requestPhoto = false;
    private volatile boolean recording = false;
    private VideoRecorder recEnhanced, recRaw;
    private Bitmap recBmpEnhanced, recBmpRaw;
    private File[] pendingVideoFiles;
    private final Object recLock = new Object();
    private final DogArray_I8 videoTmp = new DogArray_I8();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // ------------------------------------------------------------------ ui
    private GestureDetector gestures;
    private ImageButton shutter;
    private TextView recIndicator;
    private boolean shutterPressed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable startVideoRunnable = this::startVideo;

    private final Paint paintText = new Paint();
    private final Paint paintBar = new Paint();
    private final Paint paintBarBg = new Paint();

    public HistogramCameraActivity() {
        super(BitmapMode.DOUBLE_BUFFER);
        super.visualizeOnlyMostRecent = true;
        super.targetResolution = computeTargetResolution();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_histogram);

        enhanced = enhancedGray;
        setupPaints();
        setupUi();
        enumerateCameras();

        if (hasPermissions()) {
            startEverything();
        } else {
            requestPermissions();
        }
    }

    // ================================================================== setup

    private void setupPaints() {
        float density = getResources().getDisplayMetrics().density;
        paintText.setColor(Color.WHITE);
        paintText.setAntiAlias(true);
        paintText.setTextSize(14 * density);
        paintText.setShadowLayer(4 * density, 0, 0, Color.BLACK);

        paintBar.setColor(0xFFFF4081);
        paintBar.setStyle(Paint.Style.FILL);

        paintBarBg.setColor(0x66000000);
        paintBarBg.setStyle(Paint.Style.FILL);
    }

    private void setupUi() {
        shutter = findViewById(R.id.btn_shutter);
        recIndicator = findViewById(R.id.rec_indicator);

        findViewById(R.id.btn_mode).setOnClickListener(v -> toggleMode());
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettingsDialog());

        shutter.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    shutterPressed = true;
                    handler.postDelayed(startVideoRunnable, 350);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(startVideoRunnable);
                    if (recording) {
                        stopVideo();
                    } else if (shutterPressed) {
                        takePhoto();
                    }
                    shutterPressed = false;
                    return true;
                default:
                    return false;
            }
        });
    }

    private void startEverything() {
        setupImageType();
        FrameLayout frame = findViewById(R.id.camera_frame);
        startCamera(frame, null);
        setupGestures();
    }

    private void setupImageType() {
        if (colorMode) {
            setImageType(ImageType.pl(3, GrayU8.class), ColorFormat.RGB);
        } else {
            setImageType(ImageType.single(GrayU8.class));
        }
    }

    private void setupGestures() {
        gestures = new GestureDetector(this, new GestureListener());
        if (displayView != null) {
            displayView.setOnTouchListener((v, e) -> {
                gestures.onTouchEvent(e);
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    showRaw = false;
                }
                return true;
            });
        }
    }

    private void enumerateCameras() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (manager != null) {
                cameraIds = manager.getCameraIdList();
                for (int i = 0; i < cameraIds.length; i++) {
                    CameraCharacteristics c = manager.getCameraCharacteristics(cameraIds[i]);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        cameraIndex = i;
                        break;
                    }
                }
            }
        } catch (CameraAccessException | IllegalArgumentException e) {
            e.printStackTrace();
        }
        if (cameraIds.length == 0) {
            cameraIds = new String[]{"0"};
            cameraIndex = 0;
        }
    }

    // ================================================================== camera overrides

    @Override
    protected boolean selectCamera(CameraID camera, CameraCharacteristics characteristics) {
        if (cameraIds.length == 0)
            return true;
        int idx = Math.max(0, Math.min(cameraIndex, cameraIds.length - 1));
        return camera.id.equals(cameraIds[idx]);
    }

    @Override
    protected void onCameraResolutionChange(int width, int height, int sensorOrientation) {
        super.onCameraResolutionChange(width, height, sensorOrientation);
        enhancedGray.reshape(width, height);
        enhancedColor.reshape(width, height);
        gray.reshape(width, height);
        if (recording)
            runOnUiThread(this::stopVideo);
    }

    // ================================================================== processing

    @Override
    protected void processImage(ImageBase image) {
        long now = System.nanoTime();
        if (lastFrameNanos > 0) {
            double dt = (now - lastFrameNanos) * 1e-9;
            if (dt > 0) {
                double inst = 1.0 / dt;
                fps = (fps == 0) ? inst : 0.9 * fps + 0.1 * inst;
            }
        }
        lastFrameNanos = now;

        if (image instanceof Planar) {
            //noinspection unchecked
            processColor((Planar<GrayU8>) image);
            enhanced = enhancedColor;
        } else {
            processGray((GrayU8) image);
            enhanced = enhancedGray;
        }

        if (showOverlay)
            computeDisplayHistogram(image);

        if (requestPhoto) {
            requestPhoto = false;
            capturePhoto(image);
        }
        if (recording)
            feedRecorders(image);
    }

    private void processGray(GrayU8 input) {
        if (mode == Mode.GLOBAL) {
            ImageStatistics.histogram(input, 0, histogram);
            EnhanceImageOps.equalize(histogram, transform);
            EnhanceImageOps.applyTransform(input, transform, enhancedGray);
        } else {
            // histogramLength must be 256 for an 8-bit image; "bins" is applied below as a quantize.
            EnhanceImageOps.equalizeLocal(input, radius, enhancedGray, 256, work);
        }
        quantize(enhancedGray);
    }

    private void processColor(Planar<GrayU8> input) {
        int bands = input.getNumBands();
        if (mode == Mode.GLOBAL) {
            ConvertImage.average(input, gray);
            ImageStatistics.histogram(gray, 0, histogram);
            EnhanceImageOps.equalize(histogram, transform);
            for (int i = 0; i < bands; i++)
                EnhanceImageOps.applyTransform(input.getBand(i), transform, enhancedColor.getBand(i));
        } else {
            for (int i = 0; i < bands; i++)
                EnhanceImageOps.equalizeLocal(input.getBand(i), radius, enhancedColor.getBand(i), 256, work);
        }
        for (int i = 0; i < bands; i++)
            quantize(enhancedColor.getBand(i));
    }

    /**
     * Optional posterize step controlled by the "histogram bins" setting: reduces the number of
     * distinct intensity levels, changing contrast granularity. A no-op when bins == 256.
     */
    private void quantize(GrayU8 img) {
        if (histBins >= 256)
            return;
        if (binLutFor != histBins) {
            int levels = Math.max(2, histBins);
            for (int v = 0; v < 256; v++) {
                int q = Math.round(v * (levels - 1) / 255f);
                binLut[v] = Math.round(q * 255f / (levels - 1));
            }
            binLutFor = histBins;
        }
        byte[] d = img.data;
        for (int y = 0; y < img.height; y++) {
            int idx = img.startIndex + y * img.stride;
            for (int x = 0; x < img.width; x++, idx++)
                d[idx] = (byte) binLut[d[idx] & 0xFF];
        }
    }

    private void computeDisplayHistogram(ImageBase image) {
        GrayU8 g;
        if (image instanceof Planar) {
            //noinspection unchecked
            ConvertImage.average((Planar<GrayU8>) image, gray);
            g = gray;
        } else {
            g = (GrayU8) image;
        }
        ImageStatistics.histogram(g, 0, displayHistogram);
    }

    @Override
    protected void renderBitmapImage(BitmapMode mode, ImageBase image) {
        ImageBase src = showRaw ? image : enhanced;
        if (src == null)
            src = image;
        switch (mode) {
            case UNSAFE:
                ConvertBitmap.boofToBitmap(src, bitmap, bitmapTmp);
                break;

            case DOUBLE_BUFFER:
                ConvertBitmap.boofToBitmap(src, bitmapWork, bitmapTmp);
                if (bitmapLock.tryLock()) {
                    try {
                        Bitmap tmp = bitmapWork;
                        bitmapWork = bitmap;
                        bitmap = tmp;
                    } finally {
                        bitmapLock.unlock();
                    }
                }
                break;

            default:
                break;
        }
    }

    @Override
    protected void onDrawFrame(SurfaceView view, Canvas canvas) {
        super.onDrawFrame(view, canvas);
        float density = getResources().getDisplayMetrics().density;
        if (showInfo)
            drawInfo(canvas, density);
        if (showOverlay)
            drawHistogramOverlay(canvas, density);
    }

    private void drawInfo(Canvas canvas, float density) {
        String line1 = (mode == Mode.GLOBAL ? "GLOBAL" : "LOCAL")
                + (colorMode ? " · Color" : " · Gray");
        String line2 = bitmap.getWidth() + "x" + bitmap.getHeight()
                + " · cam " + cameraIndex
                + " · " + String.format(Locale.US, "%.0f fps", fps)
                + (downscale > 1 ? " · /" + downscale : "");
        float x = 16 * density;
        float y = 40 * density;
        canvas.drawText(line1, x, y, paintText);
        canvas.drawText(line2, x, y + 20 * density, paintText);
    }

    private void drawHistogramOverlay(Canvas canvas, float density) {
        int max = 1;
        for (int v : displayHistogram)
            if (v > max) max = v;

        float boxW = Math.min(canvas.getWidth() * 0.6f, 256 * density * 0.8f);
        float boxH = 60 * density;
        float left = 16 * density;
        float top = canvas.getHeight() - boxH - 100 * density;
        float pad = 6 * density;

        canvas.drawRect(left, top, left + boxW + 2 * pad, top + boxH + 2 * pad, paintBarBg);

        float barW = boxW / 256f;
        float baseY = top + pad + boxH;
        for (int i = 0; i < 256; i++) {
            float h = boxH * displayHistogram[i] / (float) max;
            float bx = left + pad + i * barW;
            canvas.drawRect(bx, baseY - h, bx + barW, baseY, paintBar);
        }
    }

    // ================================================================== controls

    private void toggleMode() {
        mode = (mode == Mode.GLOBAL) ? Mode.LOCAL : Mode.GLOBAL;
        toast(getString(mode == Mode.GLOBAL ? R.string.mode_global : R.string.mode_local));
    }

    private void changeResolution(int dir) {
        int next = Math.min(BUCKET.length - 1, Math.max(0, resIndex + dir));
        if (next == resIndex)
            return;
        resIndex = next;
        targetResolution = computeTargetResolution();
        toast("Resolution: " + BUCKET_NAME[resIndex]);
        restartCamera();
    }

    private void switchCamera(int dir) {
        if (cameraIds.length <= 1) {
            toast("Only one camera available");
            return;
        }
        cameraIndex = (cameraIndex + dir + cameraIds.length) % cameraIds.length;
        if (recording)
            stopVideo();
        toast("Camera " + cameraIndex);
        restartCamera();
    }

    private int computeTargetResolution() {
        int base = BUCKET[resIndex];
        int t = base / (downscale * downscale);
        return Math.max(MIN_PIXELS, t);
    }

    private void restartCamera() {
        runOnUiThread(() -> {
            closeCamera();
            openCamera(viewWidth, viewHeight);
        });
    }

    private void showSettingsDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_settings, null);

        android.widget.TextView labelRadius = content.findViewById(R.id.label_radius);
        android.widget.TextView labelBins = content.findViewById(R.id.label_bins);
        android.widget.SeekBar seekRadius = content.findViewById(R.id.seek_radius);
        android.widget.SeekBar seekBins = content.findViewById(R.id.seek_bins);
        android.widget.RadioGroup groupDs = content.findViewById(R.id.group_downscale);
        android.widget.Switch switchColor = content.findViewById(R.id.switch_color);
        android.widget.Switch switchInfo = content.findViewById(R.id.switch_info);
        android.widget.Switch switchOverlay = content.findViewById(R.id.switch_overlay);

        seekRadius.setProgress(radius - 5);
        labelRadius.setText(getString(R.string.radius_label) + ": " + radius);
        seekRadius.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                radius = p + 5;
                labelRadius.setText(getString(R.string.radius_label) + ": " + radius);
            }
        });

        seekBins.setProgress(histBins - 16);
        labelBins.setText(getString(R.string.bins_label) + ": " + histBins);
        seekBins.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                histBins = p + 16;
                labelBins.setText(getString(R.string.bins_label) + ": " + histBins);
            }
        });

        int checkedId = downscale == 4 ? R.id.ds_4 : (downscale == 2 ? R.id.ds_2 : R.id.ds_1);
        groupDs.check(checkedId);

        switchColor.setChecked(colorMode);
        switchInfo.setChecked(showInfo);
        switchOverlay.setChecked(showOverlay);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(content)
                .setPositiveButton(R.string.close, (d, w) -> {
                    showInfo = switchInfo.isChecked();
                    showOverlay = switchOverlay.isChecked();

                    int newDownscale = 1;
                    int checked = groupDs.getCheckedRadioButtonId();
                    if (checked == R.id.ds_2) newDownscale = 2;
                    else if (checked == R.id.ds_4) newDownscale = 4;

                    boolean newColor = switchColor.isChecked();
                    boolean restart = false;

                    if (newDownscale != downscale) {
                        downscale = newDownscale;
                        targetResolution = computeTargetResolution();
                        restart = true;
                    }
                    if (newColor != colorMode) {
                        colorMode = newColor;
                        setupImageType();
                        restart = true;
                    }
                    if (restart)
                        restartCamera();
                })
                .show();
    }

    // ================================================================== capture

    private void takePhoto() {
        requestPhoto = true;
    }

    /** Runs on the processing thread; copies the current frame and saves it off the UI thread. */
    private void capturePhoto(ImageBase input) {
        final Bitmap enh = toBitmap(enhanced != null ? enhanced : input);
        final Bitmap raw = toBitmap(input);
        final String stamp = timestamp();
        io.execute(() -> {
            String loc = MediaSaver.savePhoto(this, enh, "BMCV_" + stamp + "_enhanced");
            MediaSaver.savePhoto(this, raw, "BMCV_" + stamp + "_raw");
            final String where = loc != null ? loc : "gallery";
            runOnUiThread(() -> toast("Saved photo (enhanced + raw) → " + where));
            enh.recycle();
            raw.recycle();
        });
    }

    private Bitmap toBitmap(ImageBase image) {
        Bitmap bmp = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        ConvertBitmap.boofToBitmap(image, bmp, new DogArray_I8());
        return bmp;
    }

    private void startVideo() {
        if (recording)
            return;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        String stamp = timestamp();
        try {
            File fEnh = MediaSaver.newVideoFile(this, "BMCV_" + stamp + "_enhanced");
            File fRaw = MediaSaver.newVideoFile(this, "BMCV_" + stamp + "_raw");
            recEnhanced = new VideoRecorder(fEnh, w, h, 30);
            recRaw = new VideoRecorder(fRaw, w, h, 30);
            recEnhanced.start();
            recRaw.start();
            recBmpEnhanced = Bitmap.createBitmap(recEnhanced.getWidth(), recEnhanced.getHeight(), Bitmap.Config.ARGB_8888);
            recBmpRaw = Bitmap.createBitmap(recRaw.getWidth(), recRaw.getHeight(), Bitmap.Config.ARGB_8888);
            pendingVideoFiles = new File[]{fEnh, fRaw};
            recording = true;
            shutter.setImageResource(R.drawable.shutter_video);
            recIndicator.setVisibility(View.VISIBLE);
            toast("Recording…");
        } catch (Exception e) {
            e.printStackTrace();
            synchronized (recLock) {
                safeStop(recEnhanced);
                safeStop(recRaw);
                recEnhanced = null;
                recRaw = null;
            }
            toast("Video start failed");
        }
    }

    private void feedRecorders(ImageBase input) {
        synchronized (recLock) {
            if (!recording)
                return;
            try {
                if (recEnhanced != null && enhanced != null
                        && recBmpEnhanced.getWidth() == enhanced.getWidth()
                        && recBmpEnhanced.getHeight() == enhanced.getHeight()) {
                    ConvertBitmap.boofToBitmap(enhanced, recBmpEnhanced, videoTmp);
                    recEnhanced.encodeFrame(recBmpEnhanced);
                }
                if (recRaw != null
                        && recBmpRaw.getWidth() == input.getWidth()
                        && recBmpRaw.getHeight() == input.getHeight()) {
                    ConvertBitmap.boofToBitmap(input, recBmpRaw, videoTmp);
                    recRaw.encodeFrame(recBmpRaw);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void stopVideo() {
        if (!recording)
            return;
        recording = false;
        shutter.setImageResource(R.drawable.shutter_photo);
        recIndicator.setVisibility(View.GONE);

        final VideoRecorder e = recEnhanced;
        final VideoRecorder r = recRaw;
        final File[] files = pendingVideoFiles;
        recEnhanced = null;
        recRaw = null;
        pendingVideoFiles = null;

        io.execute(() -> {
            synchronized (recLock) {
                safeStop(e);
                safeStop(r);
            }
            if (files != null)
                for (File f : files)
                    MediaSaver.registerVideo(this, f);
            runOnUiThread(() -> toast("Saved video (enhanced + raw)"));
        });
    }

    private static void safeStop(VideoRecorder recorder) {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
        }
    }

    // ================================================================== gestures

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        private final float minDistance;
        private final float minVelocity;

        GestureListener() {
            float density = getResources().getDisplayMetrics().density;
            minDistance = 80 * density; // "middle" sensitivity
            minVelocity = 120 * density;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            showRaw = true; // before/after compare while held
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            showOverlay = !showOverlay;
            toast(showOverlay ? "Histogram overlay on" : "Histogram overlay off");
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (e1 == null || e2 == null)
                return false;
            float dx = e2.getX() - e1.getX();
            float dy = e2.getY() - e1.getY();

            if (Math.abs(dx) > Math.abs(dy)) {
                if (Math.abs(dx) > minDistance && Math.abs(velocityX) > minVelocity) {
                    // right = higher resolution, left = lower
                    changeResolution(dx > 0 ? 1 : -1);
                    return true;
                }
            } else {
                if (Math.abs(dy) > minDistance && Math.abs(velocityY) > minVelocity) {
                    // up = next camera, down = previous
                    switchCamera(dy < 0 ? 1 : -1);
                    return true;
                }
            }
            return false;
        }
    }

    /** Convenience adapter so settings sliders only implement onProgressChanged. */
    private abstract static class SimpleSeek implements android.widget.SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
        }
    }

    // ================================================================== permissions

    private boolean hasPermissions() {
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean storage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        return camera && storage;
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSIONS);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean cameraGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.CAMERA.equals(permissions[i])
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    cameraGranted = true;
                }
            }
            if (cameraGranted) {
                startEverything();
            } else {
                toast("Camera permission is required");
                finish();
            }
        }
    }

    // ================================================================== helpers

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}
