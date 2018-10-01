/*
 * Copyright (c) 2017 Illuminoo Projects BV.
 * This file is part of LISA and subject to the to the terms and conditions defined in file 'LICENSE',
 * which is part of this source code package.
 */
package net.ossrs.yasea;

/**
 * Implements an audio/video frame
 */
public class Frame {

    /**
     * Key frame
     */
    public boolean keyframe;

    /**
     * Timestamp
     */
    public long timestamp;

    /**
     * Encoded frame
     */
    public byte[] data;
}
