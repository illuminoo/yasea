/*
 * Copyright (c) 2017 Illuminoo Projects BV.
 * This file is part of LISA and subject to the to the terms and conditions defined in file 'LICENSE',
 * which is part of this source code package.
 */

package net.ossrs.yasea;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Implements an Advanced Audio Codec encoder
 */
public class SrsAacEncoder {
    private static final String TAG = "SrsAacEncoder";

    public static final String ACODEC = MediaFormat.MIMETYPE_AUDIO_AAC;
    public static final int ASAMPLERATE = 44100;
    public static int aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
    public static final int ABITRATE = 128 * 1000;  // 128 kbps
    private static final int PCM_BUFFER_SIZE = AudioRecord.getMinBufferSize(ASAMPLERATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);

    private final byte[] mPcmBuffer;
    public final MediaFormat mediaFormat;
    private final String codecName;
    private final int source;

    private MediaCodec aencoder;
    private MediaCodec.BufferInfo aebi = new MediaCodec.BufferInfo();
    private final MediaCodec.Callback handler;

    /**
     * Microphone
     */
    private AudioRecord mic;

    /**
     * Echo canceler
     */
    private AcousticEchoCanceler aec;

    /**
     * Gain controller
     */
    private AutomaticGainControl agc;

    /**
     * Background thread for audio
     */
    private HandlerThread audioThread;

    /**
     * Constructor
     *
     * @param source  Audio source
     * @param handler Codec handler
     */
    public SrsAacEncoder(int source, MediaCodec.Callback handler) {
        this.handler = handler;
        this.source = source;
        mPcmBuffer = new byte[PCM_BUFFER_SIZE];

        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        mediaFormat = getMediaFormat();
        codecName = list.findEncoderForFormat(mediaFormat);
    }

    /**
     * @return Media format
     */
    public static MediaFormat getMediaFormat() {
        MediaFormat mediaFormat = MediaFormat.createAudioFormat(ACODEC, ASAMPLERATE, 2);
        mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, ABITRATE);
        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_BUFFER_SIZE);
        return mediaFormat;
    }

    /**
     * Start encoder
     *
     * @return True when successful
     */
    public void start() throws IOException {

        // Prepare microphone
        mic = new AudioRecord(source, SrsAacEncoder.ASAMPLERATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, PCM_BUFFER_SIZE);
        if (mic == null)
            throw new IOException("Specified audio source is not supported by this device");

        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(mic.getAudioSessionId());
            if (aec != null) aec.setEnabled(true);
        }

        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(mic.getAudioSessionId());
            if (agc != null) agc.setEnabled(true);
        }
        mic.startRecording();

        // Start Audio encoder
        try {
            aencoder = MediaCodec.createByCodecName(codecName);
            aencoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            if (handler != null) {
                audioThread = new HandlerThread("Audio");
                audioThread.start();
                aencoder.setCallback(handler, new Handler(audioThread.getLooper()));
            }
            aencoder.start();
            Log.i(TAG, "Started");
        } catch (Exception e) {
            throw new IOException("Required audio encoder or settings are not supported by this device", e);
        }
    }

    public void stop() {
        if (aencoder != null) {
            Log.i(TAG, "stop aencoder");
            aencoder.stop();
            mic.stop();
        }

        if (audioThread != null) {
            Log.i(TAG, "stop background thread");
            audioThread.quitSafely();
            audioThread = null;
        }

        if (aencoder != null) {
            Log.i(TAG, "release aencoder");
            aencoder.release();
            aencoder = null;
        }

        if (mic != null) {
            mic.setRecordPositionUpdateListener(null);
            mic.release();
            mic = null;
        }

        if (aec != null) {
            aec.setEnabled(false);
            aec.release();
            aec = null;
        }

        if (agc != null) {
            agc.setEnabled(false);
            agc.release();
            agc = null;
        }
    }

    /**
     * Capture audio frames
     */
    public void captureAudio() {
        int inBufferIndex = aencoder.dequeueInputBuffer(0);
        if (inBufferIndex >= 0) {
            try {
                long pts = System.nanoTime() / 1000;
                int size = mic.read(mPcmBuffer, 0, mPcmBuffer.length);
                if (size > 0) {
                    ByteBuffer bb = aencoder.getInputBuffer(inBufferIndex);
                    bb.put(mPcmBuffer, 0, size);
                    aencoder.queueInputBuffer(inBufferIndex, 0, size, pts, 0);
                }
            } catch (IllegalStateException e) {
                // Ignore
            }
        }
    }

    /**
     * Get encoded AAC data
     *
     * @param frame Destination frame
     * @return Destination frame set successfully
     */
    public boolean getAAC(Frame frame) {
        int outBufferIndex = aencoder.dequeueOutputBuffer(aebi, 0);
        if (outBufferIndex >= 0) {
            ByteBuffer bb = aencoder.getOutputBuffer(outBufferIndex);
            frame.flags = aebi.flags;
            frame.timestamp = aebi.presentationTimeUs;
            frame.data = new byte[aebi.size];
            bb.get(frame.data);
            aencoder.releaseOutputBuffer(outBufferIndex, false);
            return true;
        }
        return false;
    }
}
