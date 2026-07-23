package org.boofcv.boof_mystic_cv;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Minimal, audio-free H.264 video recorder that accepts already-rendered {@link Bitmap} frames
 * (the processed histogram output, or the raw camera frame) and muxes them into an MP4.
 *
 * <p>Frames are pushed with {@link #encodeFrame(Bitmap)} from the image-processing thread. Pixels
 * are converted from ARGB to YUV420 and written into the encoder's input {@link Image}, honouring
 * each plane's row/pixel stride so it works across the various device colour layouts.</p>
 *
 * <p>Note: this is a pragmatic software feed. On lower-end devices the encoder may not keep up with
 * full-resolution frames and some frames will be dropped; use the "Processing downscale" setting to
 * lighten the load.</p>
 */
public class VideoRecorder {

    private static final String TAG = "VideoRecorder";
    private static final String MIME = "video/avc";
    private static final long DEQUEUE_TIMEOUT_US = 10_000;

    private final File outputFile;
    private final int width;
    private final int height;
    private final int frameRate;
    private final int bitRate;

    private MediaCodec encoder;
    private MediaMuxer muxer;
    private MediaCodec.BufferInfo bufferInfo;
    private int trackIndex = -1;
    private boolean muxerStarted;
    private long startNanos = -1;

    private int[] argb; // reused pixel scratch

    public VideoRecorder(File outputFile, int width, int height, int frameRate) {
        // Encoders generally require even dimensions.
        this.outputFile = outputFile;
        this.width = width & ~1;
        this.height = height & ~1;
        this.frameRate = frameRate;
        // ~ a reasonable quality/size trade-off scaled with resolution
        this.bitRate = Math.max(2_000_000, (int) (this.width * (long) this.height * frameRate * 0.12));
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void start() throws Exception {
        bufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        encoder = MediaCodec.createEncoderByType(MIME);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxerStarted = false;
        trackIndex = -1;
        startNanos = -1;
        argb = new int[width * height];
    }

    /** Encodes a single frame. The bitmap must match the recorder's dimensions. */
    public void encodeFrame(Bitmap bitmap) {
        if (encoder == null)
            return;
        if (bitmap.getWidth() != width || bitmap.getHeight() != height)
            return; // size changed underneath us; skip

        int inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
        if (inputIndex < 0) {
            drain(false); // give the encoder a chance to release buffers
            return;
        }

        Image image = encoder.getInputImage(inputIndex);
        if (image == null) {
            encoder.queueInputBuffer(inputIndex, 0, 0, 0, 0);
            return;
        }

        fillYuv420(image, bitmap);

        if (startNanos < 0)
            startNanos = System.nanoTime();
        long ptsUs = (System.nanoTime() - startNanos) / 1000L;

        // Size of a YUV420 frame = w*h (Y) + 2 * (w/2*h/2) (U,V)
        int frameSize = width * height * 3 / 2;
        encoder.queueInputBuffer(inputIndex, 0, frameSize, ptsUs, 0);

        drain(false);
    }

    public void stop() {
        if (encoder == null)
            return;
        try {
            int inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
            if (inputIndex >= 0) {
                long ptsUs = startNanos < 0 ? 0 : (System.nanoTime() - startNanos) / 1000L;
                encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            drain(true);
        } catch (Exception e) {
            Log.e(TAG, "stop drain failed", e);
        } finally {
            release();
        }
    }

    private void release() {
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        } catch (Exception ignored) {
        }
        encoder = null;
        try {
            if (muxer != null) {
                if (muxerStarted)
                    muxer.stop();
                muxer.release();
            }
        } catch (Exception ignored) {
        }
        muxer = null;
        argb = null;
    }

    private void drain(boolean endOfStream) {
        while (true) {
            int outIndex = encoder.dequeueOutputBuffer(bufferInfo, endOfStream ? DEQUEUE_TIMEOUT_US : 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream)
                    break;
                // else keep waiting for EOS
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted)
                    throw new IllegalStateException("format changed twice");
                MediaFormat newFormat = encoder.getOutputFormat();
                trackIndex = muxer.addTrack(newFormat);
                muxer.start();
                muxerStarted = true;
            } else if (outIndex >= 0) {
                ByteBuffer encoded = encoder.getOutputBuffer(outIndex);
                if (encoded != null && bufferInfo.size != 0 && muxerStarted) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encoded.position(bufferInfo.offset);
                        encoded.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo);
                    }
                }
                encoder.releaseOutputBuffer(outIndex, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                    break;
            }
        }
    }

    /** Converts an ARGB bitmap into the encoder input Image (YUV 4:2:0, BT.601 limited range). */
    private void fillYuv420(Image image, Bitmap bitmap) {
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixStride = planes[2].getPixelStride();

        for (int row = 0; row < height; row++) {
            int argbRow = row * width;
            int yLine = row * yRowStride;
            int cLine = (row >> 1);
            for (int col = 0; col < width; col++) {
                int c = argb[argbRow + col];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yBuf.put(yLine + col * yPixStride, (byte) clamp(y));

                if ((row & 1) == 0 && (col & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    int cCol = (col >> 1);
                    uBuf.put(cLine * uRowStride + cCol * uPixStride, (byte) clamp(u));
                    vBuf.put(cLine * vRowStride + cCol * vPixStride, (byte) clamp(v));
                }
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
