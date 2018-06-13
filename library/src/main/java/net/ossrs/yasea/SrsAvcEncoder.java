/*
 * Copyright (c) 2017 Illuminoo Projects BV.
 * This file is part of LISA and subject to the to the terms and conditions defined in file 'LICENSE',
 * which is part of this source code package.
 */

package net.ossrs.yasea;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Implements an Advanced Video Codec encoder (H264)
 */
public class SrsAvcEncoder {
    private static final String TAG = "SrsAvcEncoder";

    public static final String CODEC = "video/avc";
    public static final int VFPS = 30;
    public static final int VGOP = 60;

    private final String x264Preset;
    public final int outWidth;
    public final int outHeight;
    public final int vBitrate;

    public MediaFormat mediaFormat;
    private final MediaCodec.BufferInfo vebi = new MediaCodec.BufferInfo();
    private final int colorFormat;
    private final String codecName;
    private MediaCodec vencoder;

    private final int inWidth;
    private final int inHeight;

    private int rotate = 0;
    private int rotateFlip = 180;

    private final byte[] y_frame;
    private final byte[] u_frame;
    private final byte[] v_frame;
    private final int[] argb_frame;

    private final Bitmap overlayBitmap;
    private final Canvas overlay;

    private HandlerThread videoThread;

    private MediaCodec.Callback handler;

    /**
     * Implements an AVC encoder
     *
     * @param inWidth   Input width
     * @param inHeight  Input height
     * @param outWidth  Output width
     * @param outHeight Output height
     * @param HD        Full HD mode
     */
    public SrsAvcEncoder(int inWidth, int inHeight, int outWidth, int outHeight, boolean HD, MediaCodec.Callback handler) {
        this.handler = handler;

        // Prepare input
        this.inWidth = inWidth;
        this.inHeight = inHeight;
        y_frame = new byte[inWidth * inHeight];
        u_frame = new byte[(inWidth * inHeight) / 2 - 1];
        v_frame = new byte[(inWidth * inHeight) / 2 - 1];

        // Prepare video overlay
        overlayBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        overlay = new Canvas(overlayBitmap);

        // Prepare output
        this.outWidth = outWidth;
        this.outHeight = outHeight;
        argb_frame = new int[outWidth * outHeight];

        setEncoderResolution(outWidth, outHeight);
        setEncoderFps(VFPS);
        setEncoderGop(VGOP);

        if (HD) {
            vBitrate = 4800 * 1024;
            x264Preset = "veryfast";
        } else {
            vBitrate = 1200 * 1024;
            x264Preset = "superfast";
        }
        setEncoderBitrate(vBitrate);
        setEncoderPreset(x264Preset);

        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
        mediaFormat = MediaFormat.createVideoFormat(CODEC, outWidth, outHeight);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
//        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, vBitrate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VFPS);
        mediaFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, VFPS);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VGOP / VFPS);
        codecName = list.findEncoderForFormat(mediaFormat);
    }

    /**
     * Start encoder
     *
     * @return True when successful
     */
    public boolean start() {
        try {
            vencoder = MediaCodec.createByCodecName(codecName);
            vencoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            if (handler!=null) {
                videoThread = new HandlerThread("Video", Process.THREAD_PRIORITY_AUDIO);
                videoThread.start();
                vencoder.setCallback(handler, new Handler(videoThread.getLooper()));
            }
            vencoder.start();

            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start encoder", e);
        }
        return false;
    }

    public void stop() {
        if (videoThread != null) {
            Log.i(TAG, "stop background thread");
            videoThread.quit();
            videoThread = null;
        }

        if (vencoder != null) {
            Log.i(TAG, "Stop encoder");
            vencoder.stop();
            vencoder.release();
            vencoder = null;
        }
    }

    public void setCameraOrientation(int degrees) {
        if (degrees < 0) {
            rotate = 360 + degrees;
            rotateFlip = 180 - degrees;
        } else {
            rotate = degrees;
            rotateFlip = 180 + degrees;
        }
    }

    private void encodeYuvFrame(byte[] yuvFrame) {
        encodeYuvFrame(yuvFrame, System.nanoTime()/1000);
    }

    private void encodeYuvFrame(byte[] yuvFrame, long pts) {
        int inBufferIndex = vencoder.dequeueInputBuffer(0);
        if (inBufferIndex >= 0) {
            encodeYuvFrame(yuvFrame, inBufferIndex, pts);
        }
    }

    public void encodeYuvFrame(byte[] yuvFrame, int index, long pts) {
        ByteBuffer bb = vencoder.getInputBuffer(index);
        bb.put(yuvFrame, 0, yuvFrame.length);
        vencoder.queueInputBuffer(index, 0, yuvFrame.length, pts, 0);
    }

    public boolean getH264Frame(Frame frame) {
        int outBufferIndex = vencoder.dequeueOutputBuffer(vebi, 0);
        if (outBufferIndex >= 0) {
            return getH264Frame(frame, outBufferIndex, vebi);
        }
        return false;
    }

    public boolean getH264Frame(Frame frame, int index, MediaCodec.BufferInfo info) {
        ByteBuffer bb = vencoder.getOutputBuffer(index);
        frame.video = new byte[info.size];
        bb.get(frame.video, 0, info.size);
        frame.keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        frame.timestamp = info.presentationTimeUs;
        vencoder.releaseOutputBuffer(index, false);
        return true;
    }

    public void onGetRgbaFrame(byte[] data, int width, int height) {
        encodeYuvFrame(RGBAtoYUV(data, width, height));
    }

    public void onGetYuvNV21Frame(byte[] data, int width, int height, Rect boundingBox) {
        encodeYuvFrame(NV21toYUV(data, width, height, boundingBox));
    }

    public void onGetYUV420_888Frame(Image image, Rect boundingBox) {
        encodeYuvFrame(YUV420_888toYUV(image, boundingBox), image.getTimestamp()/1000);
    }

    public void onGetArgbFrame(int[] data, int width, int height, Rect boundingBox) {
        encodeYuvFrame(ARGBtoYUV(data, width, height, boundingBox));
    }

    public byte[] RGBAtoYUV(byte[] data, int width, int height) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                return RGBAToI420(data, width, height, true, rotateFlip);
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return RGBAToNV12(data, width, height, true, rotateFlip);
            default:
                throw new IllegalStateException("Unsupported color format!");
        }
    }

    public byte[] NV21toYUV(byte[] data, int width, int height, Rect boundingBox) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                return NV21ToI420(data, width, height, true, rotateFlip, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height());
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return NV21ToNV12(data, width, height, true, rotateFlip, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height());
            default:
                throw new IllegalStateException("Unsupported color format!");
        }
    }

    public byte[] YUV420_888toYUV(Image image, Rect cropArea) {
        Image.Plane[] planes = image.getPlanes();
        planes[0].getBuffer().get(y_frame);
        planes[1].getBuffer().get(u_frame);
        planes[2].getBuffer().get(v_frame);
        return YUV420_888toI420(y_frame, planes[0].getRowStride(),
                u_frame, planes[1].getRowStride(),
                v_frame, planes[2].getRowStride(),
                planes[2].getPixelStride(),
                inWidth, inHeight, false, 0,
                cropArea.left, cropArea.top, cropArea.width(), cropArea.height());
    }

    public Canvas getOverlay() {
        overlayBitmap.eraseColor(Color.TRANSPARENT);
        return overlay;
    }

    public void clearOverlay() {
        ARGBToOverlay(null, outWidth, outHeight, false, 0);
    }

    public void updateOverlay() {
        overlayBitmap.getPixels(argb_frame, 0, outWidth, 0, 0, outWidth, outHeight);
        ARGBToOverlay(argb_frame, outWidth, outHeight, false, 0);
    }

    public byte[] ARGBtoYUV(int[] data, int width, int height, Rect boundingBox) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                return ARGBToI420(data, width, height, false, rotate, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height());
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return ARGBToNV12(data, width, height, false, rotate, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height());
            default:
                throw new IllegalStateException("Unsupported color format!");
        }
    }

    private native void setEncoderResolution(int outWidth, int outHeight);

    private native void setEncoderFps(int fps);

    private native void setEncoderGop(int gop);

    private native void setEncoderBitrate(int bitrate);

    private native void setEncoderPreset(String preset);

    private native byte[] RGBAToI420(byte[] frame, int width, int height, boolean flip, int rotate);

    private native byte[] RGBAToNV12(byte[] frame, int width, int height, boolean flip, int rotate);

    private native byte[] ARGBToI420(int[] frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y, int crop_width, int crop_height);

    private native void ARGBToOverlay(int[] frame, int width, int height, boolean flip, int rotate);

    private native byte[] YUV420_888toI420(byte[] y_frame, int y_stride, byte[] u_frame, int u_stride, byte[] v_frame, int v_stride, int uv_stride, int width, int height, boolean flip, int rotate, int crop_x, int crop_y, int crop_width, int crop_height);

    private native byte[] ARGBToNV12(int[] frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y, int crop_width, int crop_height);

    private native byte[] NV21ToNV12(byte[] frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y, int crop_width, int crop_height);

    private native byte[] NV21ToI420(byte[] frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y, int crop_width, int crop_height);

    private native int RGBASoftEncode(byte[] frame, int width, int height, boolean flip, int rotate, long pts);

    private native boolean openSoftEncoder();

    private native void closeSoftEncoder();

    static {
        System.loadLibrary("yuv");
        System.loadLibrary("enc");
    }
}
