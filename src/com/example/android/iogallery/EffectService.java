/**
 * Copyright (c) 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.iogallery;

import android.app.IntentService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.provider.MediaStore;
import android.renderscript.Allocation;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.RenderScript;
import android.text.format.DateUtils;
import android.util.Log;

/**
 * Service that applies duotone effect to the photo sent by
 * {@link Intent#getData()}.
 */
public class EffectService extends IntentService {
    private static final String TAG = "IoGallery";

    public EffectService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        final RenderScript rs = RenderScript.create(this);

        final Bitmap in = PhotoActivity.loadBitmap(getContentResolver(), intent.getData());
        final Bitmap out = Bitmap.createBitmap(in.getWidth(), in.getHeight(), in.getConfig());

        Log.d(TAG, "Loaded image of size w=" + in.getWidth() + ", h=" + in.getHeight());

        final Allocation inAlloc = Allocation.createFromBitmap(
                rs, in, MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
        final Allocation outAlloc = Allocation.createTyped(
                rs, inAlloc.getType(), Allocation.USAGE_SCRIPT);

        final ScriptC_effect script = new ScriptC_effect(rs, getResources(), R.raw.effect);

        script.set_strength(0.8f);
        script.set_darkColor(PhotoActivity.convertHsvToFloat4(30f, .5f, .75f));
        script.set_lightColor(PhotoActivity.convertHsvToFloat4(90f, .5f, .75f));

        final long start = System.nanoTime();

        // Run script across input bitmap
        script.forEach_root(inAlloc, outAlloc);

        // Copy result back to bitmap for persisting
        outAlloc.copyTo(out);

        final long delta = System.nanoTime() - start;
        Log.d(TAG, "Renderscript took " + (delta / 1000000) + "ms");

        // Persist output bitmap
        final String title = DateUtils.formatDateTime(this, System.currentTimeMillis(),
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
        MediaStore.Images.Media.insertImage(getContentResolver(), out, title, null);

        inAlloc.destroy();
        outAlloc.destroy();
        script.destroy();
        rs.destroy();
    }
}
