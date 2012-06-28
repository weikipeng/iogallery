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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receives newly taken photos, and starts {@link EffectService} to apply effect
 * in background.
 */
public class PhotoReceiver extends BroadcastReceiver {
    private static final String TAG = "IoGallery";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (PhotoActivity.isAutoApplyEnabled(context)) {
            // Perform effect work in IntentService
            final Intent serviceIntent = new Intent(context, EffectService.class);
            serviceIntent.setData(intent.getData());
            context.startService(serviceIntent);
        } else {
            Log.d(TAG, "Processed no-op broadcast!");
        }
    }
}
