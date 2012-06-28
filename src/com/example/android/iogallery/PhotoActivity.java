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

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.renderscript.Allocation;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Float4;
import android.renderscript.RenderScript;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

/**
 * Show a single photo, along with filter controls.
 */
public class PhotoActivity extends Activity {
    private static final String TAG = "IoGallery";

    /** Background thread for processing effect */
    private HandlerThread mEffectThread;
    private EffectHandler mEffectHandler;

    private RenderScript mRs;
    private ScriptC_effect mScript;

    /** Images in Dalvik heap */
    private Bitmap mIn;
    private Bitmap mOut;

    /** Images in Renderscript runtime */
    private Allocation mInAlloc;
    private Allocation mOutAlloc;

    private ImageView mImage;
    private ViewGroup mParams;
    private SeekBar mParamStrength;
    private SeekBar mParamDark;
    private SeekBar mParamLight;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.photo);

        mImage = (ImageView) findViewById(android.R.id.icon);
        mParams = (ViewGroup) findViewById(R.id.params);
        mParamStrength = (SeekBar) findViewById(R.id.param_strength);
        mParamDark = (SeekBar) findViewById(R.id.param_dark);
        mParamLight = (SeekBar) findViewById(R.id.param_light);

        mParamStrength.setOnSeekBarChangeListener(mParamListener);
        mParamDark.setOnSeekBarChangeListener(mParamListener);
        mParamLight.setOnSeekBarChangeListener(mParamListener);

        mRs = RenderScript.create(this);
        mScript = new ScriptC_effect(mRs, getResources(), R.raw.effect);

        // TODO: Make image loading async, but blocking here makes the
        // Allocation logic simpler to understand.

        mIn = loadBitmap(getContentResolver(), getIntent().getData());
        mOut = Bitmap.createBitmap(mIn.getWidth(), mIn.getHeight(), mIn.getConfig());

        Log.d(TAG, "Loaded image of size w=" + mIn.getWidth() + ", h=" + mIn.getHeight());

        mInAlloc = Allocation.createFromBitmap(
                mRs, mIn, MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
        mOutAlloc = Allocation.createTyped(
                mRs, mInAlloc.getType(), Allocation.USAGE_SCRIPT);

        // Create background thread that will apply effect
        mEffectThread = new HandlerThread(TAG);
        mEffectThread.start();

        mEffectHandler = new EffectHandler(mEffectThread.getLooper());

        // By default, just show original image
        mImage.setImageBitmap(mIn);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mInAlloc.destroy();
        mOutAlloc.destroy();
        mScript.destroy();
        mRs.destroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.photo, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.menu_auto_apply).setChecked(isAutoApplyEnabled(this));
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_effect: {
                setEffectModeEnabled(true);
                startActionMode(mEffectMode);
                return true;
            }
            case R.id.menu_auto_apply: {
                final boolean autoApply = !isAutoApplyEnabled(this);
                item.setChecked(autoApply);
                setAutoApplyEnabled(this, autoApply);
                return true;
            }
        }
        return false;
    }

    private ActionMode.Callback mEffectMode = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            getMenuInflater().inflate(R.menu.photo_effect, menu);
            mode.setTitle(R.string.menu_effect);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            setEffectModeEnabled(false);
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.menu_save: {
                    final String title = DateUtils.formatDateTime(PhotoActivity.this,
                            System.currentTimeMillis(),
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
                    new ImagePersistTask(title, mOut).execute();
                    mode.finish();
                    return true;
                }
            }
            return false;
        }
    };

    /**
     * Enable or disable visual effect editing mode.
     */
    private void setEffectModeEnabled(boolean enabled) {
        if (enabled) {
            mEffectHandler.obtainMessage(EffectHandler.MSG_APPLY_EFFECT).sendToTarget();
            mParams.setVisibility(View.VISIBLE);
        } else {
            mEffectHandler.removeMessages(EffectHandler.MSG_APPLY_EFFECT);
            mImage.setImageBitmap(mIn);
            mParams.setVisibility(View.GONE);
        }
    }

    private OnSeekBarChangeListener mParamListener = new OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            // User made setting change, so kick off message to apply effect,
            // ignoring if one is already pending
            if (!mEffectHandler.hasMessages(EffectHandler.MSG_APPLY_EFFECT)) {
                mEffectHandler.sendEmptyMessage(EffectHandler.MSG_APPLY_EFFECT);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            // Ignored
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // Ignored
        }
    };

    /**
     * Background handler that runs visual effect on {@link #mInAlloc}, pushing
     * result to {@link #mImage} when finished.
     */
    private class EffectHandler extends Handler {
        public static final int MSG_APPLY_EFFECT = 1;

        public EffectHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final int darkHue = mParamDark.getProgress();
            final int lightHue = mParamLight.getProgress();

            // Bind current parameters
            mScript.set_strength(mParamStrength.getProgress() / 100f);
            mScript.set_darkColor(convertHsvToFloat4(darkHue, .5f, .75f));
            mScript.set_lightColor(convertHsvToFloat4(lightHue, .5f, .75f));

            final long start = System.nanoTime();

            // Run script across input bitmap
            mScript.forEach_root(mInAlloc, mOutAlloc);
            mRs.finish();

            // Copy result back to bitmap for display
            mOutAlloc.copyTo(mOut);

            final long delta = System.nanoTime() - start;
            Log.d(TAG, "Renderscript took " + (delta / 1000000) + "ms");

            // Push result bitmap to surface
            mImage.post(new Runnable() {
                @Override
                public void run() {
                    mImage.setImageBitmap(mOut);
                }
            });
        }
    }

    /**
     * Convert the given HSV color into a RGB {@link Float4} representation.
     */
    public static Float4 convertHsvToFloat4(float h, float s, float v) {
        final float[] hsv = new float[] { h, s, v };
        final int rgb = Color.HSVToColor(hsv);
        return new Float4(Color.red(rgb), Color.green(rgb), Color.blue(rgb), Color.alpha(rgb));
    }    

    /**
     * Load and return given {@link Uri} as {@link Bitmap}.
     */
    public static Bitmap loadBitmap(ContentResolver resolver, Uri uri) {
        try {
            final InputStream is = resolver.openInputStream(uri);
            try {
                final BitmapFactory.Options opts = new BitmapFactory.Options();
                // Downsample to keep processing fast
                opts.inSampleSize = 4;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                return BitmapFactory.decodeStream(is, null, opts);
            } finally {
                is.close();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Problem reading image", e);
        }
    }

    /**
     * Task that saves an edited image to
     * {@link android.provider.MediaStore.Images}.
     */
    public class ImagePersistTask extends AsyncTask<Void, Void, Void> {
        private final String mTitle;
        private final Bitmap mBitmap;

        public ImagePersistTask(String title, Bitmap bitmap) {
            mTitle = title;
            mBitmap = bitmap;
        }

        @Override
        protected Void doInBackground(Void... params) {
            MediaStore.Images.Media.insertImage(getContentResolver(), mBitmap, mTitle, null);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            Toast.makeText(PhotoActivity.this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
        }
    }

    private static final String PREFS_FILE = "IoGallery";
    private static final String PREFS_KEY_AUTO_APPLY = "auto_apply";

    public static boolean isAutoApplyEnabled(Context context) {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getBoolean(PREFS_KEY_AUTO_APPLY, false);
    }

    private static void setAutoApplyEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(PREFS_KEY_AUTO_APPLY, enabled).apply();

        final PackageManager pm = context.getPackageManager();
        final ComponentName component = new ComponentName(context, PhotoReceiver.class);

        final int state = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP);
    }
}
