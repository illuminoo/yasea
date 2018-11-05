/*
 * Copyright (c) 2017 Illuminoo Projects BV.
 * This file is part of LISA and subject to the to the terms and conditions defined in file 'LICENSE',
 * which is part of this source code package.
 */
package net.ossrs.yasea;

import android.media.MediaCodec;

/**
 * Implements an audio/video frame
 */
public class Frame {

    /**
     * Flags
     */
    public int flags;

    /**
     * Timestamp
     */
    public long timestamp;

    /**
     * Encoded frame
     */
    public byte[] data;

    /**
     * @return Is key frame
     */
    public boolean isKeyframe() {
        return (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
    }
}
