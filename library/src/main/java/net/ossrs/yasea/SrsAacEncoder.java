/*
 * Copyright (c) 2017 Illuminoo Projects BV.
 * This file is part of LISA and subject to the to the terms and conditions defined in file 'LICENSE',
 * which is part of this source code package.
 */

package net.ossrs.yasea;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Implements an Advanced Audio Codec encoder
 */
public class SrsAacEncoder extends MediaCodec.Callback {
    private static final String TAG = "SrsAacEncoder";

    public static final String ACODEC = "audio/mp4a-latm";
    public static final int ASAMPLERATE = 44100;
    public static int aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
    public static final int ABITRATE = 128 * 1024;  // 128 kbps

    private final SrsFlvMuxer muxer;

    public final MediaFormat mediaFormat;
    private final byte[] mPcmBuffer;
    private final String codecName;

    private MediaCodec aencoder;
    private MediaCodec.BufferInfo aebi = new MediaCodec.BufferInfo();

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
     * Constructor
     * @param muxer FLV muxer
     */
    public SrsAacEncoder(SrsFlvMuxer muxer) {
        this.muxer = muxer;
        mPcmBuffer = new byte[getPcmBufferSize()];

        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        int ach = aChannelConfig == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
        mediaFormat = MediaFormat.createAudioFormat(ACODEC, ASAMPLERATE, ach);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, ABITRATE);
        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, getPcmBufferSize());
        codecName = list.findEncoderForFormat(mediaFormat);
    }

    /**
     * Start encoder
     *
     * @return True when successful
     */
    public boolean start() {

        try {
            // Prepare microphone
            mic = chooseAudioRecord();

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
            aencoder = MediaCodec.createByCodecName(codecName);
            aencoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aencoder.setCallback(this);
            aencoder.start();

            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start encoder", e);
        }

        return false;
    }

    public void stop() {
        if (aencoder != null) {
            Log.i(TAG, "stop aencoder");
            aencoder.stop();
            aencoder.release();
            aencoder = null;
        }

        if (mic != null) {
            mic.setRecordPositionUpdateListener(null);
            mic.stop();
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

    private AudioRecord chooseAudioRecord() {
        AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SrsAacEncoder.ASAMPLERATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, getPcmBufferSize() * 4);
        if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
            mic = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SrsAacEncoder.ASAMPLERATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, getPcmBufferSize() * 4);
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                mic = null;
            } else {
                SrsAacEncoder.aChannelConfig = AudioFormat.CHANNEL_IN_MONO;
            }
        } else {
            SrsAacEncoder.aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
        }

        return mic;
    }

    public int getPcmBufferSize() {
        return AudioRecord.getMinBufferSize(ASAMPLERATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
    }

    public void captureAudio() {
        int inBufferIndex = aencoder.dequeueInputBuffer(-1);
        if (inBufferIndex >= 0) {
            onInputBufferAvailable(aencoder, inBufferIndex);
        }
    }

    public void muxAudio() {
        int outBufferIndex = aencoder.dequeueOutputBuffer(aebi, 0);
        while (outBufferIndex >= 0) {
            onOutputBufferAvailable(aencoder, outBufferIndex, aebi);
            outBufferIndex = aencoder.dequeueOutputBuffer(aebi, 0);
        }
    }

    @Override
    public void onInputBufferAvailable(MediaCodec codec, int index) {
        int size = mic.read(mPcmBuffer, 0, mPcmBuffer.length);
        if (size > 0) {
            ByteBuffer bb = codec.getInputBuffer(index);
            bb.put(mPcmBuffer, 0, size);
            codec.queueInputBuffer(index, 0, size, System.currentTimeMillis() * 1000, 0);
        }
    }

    @Override
    public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
        ByteBuffer bb = codec.getOutputBuffer(index);
        muxer.writeSampleData(SrsFlvMuxer.AUDIO_TRACK, bb, info);
        codec.releaseOutputBuffer(index, false);
    }

    @Override
    public void onError(MediaCodec codec, MediaCodec.CodecException e) {

    }

    @Override
    public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {

    }
}
