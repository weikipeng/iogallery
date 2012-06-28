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

import static com.example.android.iogallery.StarredDatabaseHelper.COLUMN_ID;
import static com.example.android.iogallery.StarredDatabaseHelper.COLUMN_STARRED;
import static com.example.android.iogallery.StarredDatabaseHelper.TABLE_STARRED;

import android.content.AsyncTaskLoader;
import android.content.Context;
import android.database.Cursor;

import com.example.android.util.LongSparseBooleanArray;

/**
 * Load {@link StarredDatabaseHelper} starred status into a
 * {@link LongSparseBooleanArray} for fast binding later.
 */
public class StarredLoader extends AsyncTaskLoader<LongSparseBooleanArray> {
    private StarredDatabaseHelper mStarredDb;
    
    public StarredLoader(Context context, StarredDatabaseHelper starredDb) {
        super(context);
        mStarredDb = starredDb;
    }

    @Override
    public LongSparseBooleanArray loadInBackground() {
        final Cursor cursor = mStarredDb.getReadableDatabase().query(TABLE_STARRED,
                new String[] { COLUMN_ID, COLUMN_STARRED }, null, null, null, null, null);
        try {
            final LongSparseBooleanArray result = new LongSparseBooleanArray(cursor.getCount());
            while (cursor.moveToNext()) {
                final long id = cursor.getLong(0);
                final boolean starred = cursor.getInt(1) != 0;
                result.put(id, starred);
            }
            return result;
        } finally {
            cursor.close();
        }
    }

    @Override
    protected void onStartLoading() {
        super.onStartLoading();
        forceLoad();
    }

    @Override
    protected void onStopLoading() {
        super.onStopLoading();
        cancelLoad();
    }

    @Override
    protected void onReset() {
        super.onReset();
        cancelLoad();
    }
}
