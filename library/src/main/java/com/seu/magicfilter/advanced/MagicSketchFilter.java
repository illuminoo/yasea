/*
 * Copyright (c) 2018 Illuminoo Projects BV.
 * This file is part of LISA and subject to the to the terms and conditions defined in file 'LICENSE',
 * which is part of this source code package.
 */

package com.seu.magicfilter.advanced;

import android.opengl.GLES20;

import com.seu.magicfilter.base.gpuimage.GPUImageFilter;
import com.seu.magicfilter.utils.MagicFilterType;

import net.ossrs.yasea.R;

public class MagicSketchFilter extends GPUImageFilter{
    
    private int mSingleStepOffsetLocation;
    //0.0 - 1.0
    private int mStrengthLocation;
    
    public MagicSketchFilter(){
        super(MagicFilterType.SKETCH, R.raw.sketch);
    }

    @Override
    protected void onInit() {
        super.onInit();
        mSingleStepOffsetLocation = GLES20.glGetUniformLocation(getProgram(), "singleStepOffset");
        mStrengthLocation = GLES20.glGetUniformLocation(getProgram(), "strength");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onInitialized(){
        super.onInitialized();
        setFloat(mStrengthLocation, 0.5f);
    }

    @Override
    public void onInputSizeChanged(final int width, final int height) {
        super.onInputSizeChanged(width, height);
        setFloatVec2(mSingleStepOffsetLocation, new float[] {1.0f / width, 1.0f / height});
    }
}
