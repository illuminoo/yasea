/*
 * Copyright (c) 2018 Illuminoo BV.
 * This file is part of NOOMI and subject to the to the terms and conditions defined in file 'LICENSE',
 * which is part of this source code package.
 */

package net.ossrs.yasea;

import android.media.MediaFormat;
import android.util.Log;
import com.github.faucamp.simplertmp.RtmpHandler;
import com.github.faucamp.simplertmp.amf.AmfMap;
import com.github.faucamp.simplertmp.io.RtmpConnection;
import com.github.faucamp.simplertmp.packets.Data;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Implements a RTMP publisher
 */
public class SrsRtmpPublisher extends RtmpConnection {

    /**
     * Creation date format
     */
    public final static SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * Video media format
     */
    private MediaFormat videoFormat;

    /**
     * Audio media format
     */
    private MediaFormat audioFormat;

    /**
     * Constructs a RTMP publisher
     *
     * @param handler Callback handler
     */
    public SrsRtmpPublisher(RtmpHandler handler) {
        super(handler);
    }

    @Override
    protected void onMetaData(int streamId) {
        Log.d(TAG, "onMetaData(): Sending meta data...");
        Data metadata = new Data("@setDataFrame");
        metadata.getHeader().setMessageStreamId(streamId);
        metadata.addData("onMetaData");
        AmfMap ecmaArray = new AmfMap();

        // Generic info
        ecmaArray.setProperty("createdby", "LISA");
        ecmaArray.setProperty("creationdate", DATE_FORMAT.format(new Date()));

        // Video info
        if (videoFormat != null) {
            ecmaArray.setProperty("videocodecid", 7);
            ecmaArray.setProperty("width", videoFormat.getInteger(MediaFormat.KEY_WIDTH));
            ecmaArray.setProperty("height", videoFormat.getInteger(MediaFormat.KEY_HEIGHT));
            ecmaArray.setProperty("videodatarate", videoFormat.getInteger(MediaFormat.KEY_BIT_RATE) / 1024);
            ecmaArray.setProperty("framerate", videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE));
            ecmaArray.setProperty("avcprofile", 66);
            ecmaArray.setProperty("avclevel", 31);

            ecmaArray.setProperty("audioonly", false);
        } else {
            ecmaArray.setProperty("audioonly", true);
        }

        // Audio info
        if (audioFormat != null) {
            ecmaArray.setProperty("audiocodecid", 10);
            ecmaArray.setProperty("audiodatarate", audioFormat.getInteger(MediaFormat.KEY_BIT_RATE) / 1024);
            ecmaArray.setProperty("audiosamplerate", audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE));
            ecmaArray.setProperty("audiosamplesize", 16);
            int channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            ecmaArray.setProperty("audiochannels", channelCount);
            ecmaArray.setProperty("stereo", channelCount == 2);
            ecmaArray.setProperty("aacaot", 0);

            ecmaArray.setProperty("videoonly", false);
        } else {
            ecmaArray.setProperty("videoonly", true);
        }

        metadata.addData(ecmaArray);
        sendRtmpPacket(metadata);
    }

    /**
     * Set media format for video track
     *
     * @param format Media format
     */
    public void setVideoFormat(MediaFormat format) {
        videoFormat = format;
    }

    /**
     * Set media format for audio track
     *
     * @param format Media format
     */
    public void setAudioFormat(MediaFormat format) {
        audioFormat = format;
    }
}
