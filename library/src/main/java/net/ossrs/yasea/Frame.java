/*
 * Copyright (c) 2017 Illuminoo Projects BV.
 * This file is part of LISA and subject to the to the terms and conditions defined in file 'LICENSE',
 * which is part of this source code package.
 */
package net.ossrs.yasea;

import java.io.Serializable;

public class Frame implements Serializable {

    /**
     * Timestamp
     */
    public long timestamp;

    /**
     * Encoded video data
     */
    public byte[] video;
}
