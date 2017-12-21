/*
 * Copyright (c) 2017 Illuminoo Projects BV.
 * This file is part of LISA and subject to the to the terms and conditions defined in file 'LICENSE',
 * which is part of this source code package.
 */

package net.ossrs.yasea;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implements an Advanced Video Codec encoder (H264)
 */
public class SrsAvcEncoder {
    private static final String TAG = "SrsAvcEncoder";

    public static final String CODEC = "video/avc";
    public static String x264Preset = "veryfast";
    public static int vPrevWidth = 640;
    public static int vPrevHeight = 360;
    public static int vPortraitWidth = 720;
    public static int vPortraitHeight = 1280;
    public static int vLandscapeWidth = 1280;
    public static int vLandscapeHeight = 720;
    public static int vOutWidth = 720;   // Note: the stride of resolution must be set as 16x for hard encoding with some chip like MTK
    public static int vOutHeight = 1280;  // Since Y component is quadruple size as U and V component, the stride must be set as 32x
    public static int vBitrate = 1200 * 1024;  // 1200 kbps
    public static final int VFPS = 30;
    public static final int VGOP = 30;

    private MediaCodec vencoder;
    private MediaCodec.BufferInfo vebi = new MediaCodec.BufferInfo();

    private int colorFormat;

    private int rotate = 0;
    private int rotateFlip = 180;

    private int vInputWidth;
    private int vInputHeight;

    private byte[] y_frame;
    private byte[] u_frame;
    private byte[] v_frame;
    private int[] argb_frame;

    /**
     * Start encoder
     * @return True when successful
     */
    public boolean start() {
        try {
            setEncoderResolution(vOutWidth, vOutHeight);
            setEncoderFps(VFPS);
            setEncoderGop(VGOP);
            setEncoderBitrate(vBitrate);
            setEncoderPreset(x264Preset);

            // Find codec
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(CODEC, vOutWidth, vOutHeight);
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, vBitrate);
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VFPS);
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VGOP / VFPS);
            String codecName = list.findEncoderForFormat(mediaFormat);

            vencoder = MediaCodec.createByCodecName(codecName);
            vencoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            vencoder.start();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start encoder", e);
        }
        return false;
    }

    public void stop() {
        if (vencoder != null) {
            Log.i(TAG, "Stop encoder");
            vencoder.stop();
            vencoder.release();
            vencoder = null;
        }
    }

    public void setPreviewResolution(int width, int height) {
        vPrevWidth = width;
        vPrevHeight = height;
    }

    public void setPortraitResolution(int width, int height) {
        vOutWidth = width;
        vOutHeight = height;
        vPortraitWidth = width;
        vPortraitHeight = height;
        vLandscapeWidth = height;
        vLandscapeHeight = width;
    }

    public void setInputResolution(int width, int height) {
        vInputWidth = width;
        vInputHeight = height;

        y_frame = new byte[width * height];
        u_frame = new byte[(width * height) / 2 - 1];
        v_frame = new byte[(width * height) / 2 - 1];
    }

    public void setLandscapeResolution(int width, int height) {
        vOutWidth = width;
        vOutHeight = height;
        vLandscapeWidth = width;
        vLandscapeHeight = height;
        vPortraitWidth = height;
        vPortraitHeight = width;
    }

    public void setVideoHDMode() {
        vBitrate = 3600 * 1024;  // 3600 kbps
        x264Preset = "veryfast";
    }

    public void setVideoSmoothMode() {
        vBitrate = 1200 * 1024;  // 1200 kbps
        x264Preset = "superfast";
    }

    public int getPreviewWidth() {
        return vPrevWidth;
    }

    public int getPreviewHeight() {
        return vPrevHeight;
    }

    public int getOutputWidth() {
        return vOutWidth;
    }

    public int getOutputHeight() {
        return vOutHeight;
    }

    public void setScreenOrientation(int orientation) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            vOutWidth = vPortraitWidth;
            vOutHeight = vPortraitHeight;
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            vOutWidth = vLandscapeWidth;
            vOutHeight = vLandscapeHeight;
        }
        setEncoderResolution(vOutWidth, vOutHeight);
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

    public void encodeYuvFrame(byte[] frame) {
        encodeYuvFrame(frame, System.currentTimeMillis() * 1000);
    }

    private void encodeYuvFrame(byte[] yuvFrame, long pts) {
        int inBufferIndex = vencoder.dequeueInputBuffer(-1);
        if (inBufferIndex >= 0) {
            ByteBuffer bb = vencoder.getInputBuffer(inBufferIndex);
            bb.put(yuvFrame, 0, yuvFrame.length);
            vencoder.queueInputBuffer(inBufferIndex, 0, yuvFrame.length, pts, 0);
        }
    }

    public boolean getH264Frame(Frame frame) {
        int outBufferIndex = vencoder.dequeueOutputBuffer(vebi, 0);
        if (outBufferIndex >= 0) {
            ByteBuffer bb = vencoder.getOutputBuffer(outBufferIndex);
            frame.video = new byte[vebi.size];
            bb.get(frame.video, 0, vebi.size);
            frame.keyframe = (vebi.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            frame.timestamp = vebi.presentationTimeUs;
            vencoder.releaseOutputBuffer(outBufferIndex, false);
            return true;
        }
        return false;
    }

    public void onGetRgbaFrame(byte[] data, int width, int height) {
        encodeYuvFrame(RGBAtoYUV(data, width, height));
    }

    public void onGetYuvNV21Frame(byte[] data, int width, int height, Rect boundingBox) {
        encodeYuvFrame(NV21toYUV(data, width, height, boundingBox));
    }

    public void onGetYUV420_888Frame(Image image, Rect boundingBox) {
        encodeYuvFrame(YUV420_888toYUV(image, boundingBox));
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
        return YUV420_888toI420(y_frame, u_frame, v_frame, vInputWidth, vInputHeight, false, 0,
                cropArea.left, cropArea.top, cropArea.width(), cropArea.height());
    }

    public void setOverlay(Bitmap overlay) {
        if (overlay == null) return;
        if (argb_frame == null) {
            argb_frame = new int[vOutWidth * vOutHeight];
        }
        overlay.getPixels(argb_frame, 0, vOutWidth, 0, 0, vOutWidth, vOutHeight);
        ARGBToOverlay(argb_frame, vOutWidth, vOutHeight, false, 0);
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

    private native byte[] YUV420_888toI420(byte[] y_frame, byte[] u_frame, byte[] v_frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y, int crop_width, int crop_height);

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
