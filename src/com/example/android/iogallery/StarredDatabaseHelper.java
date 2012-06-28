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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;

/**
 * Database holding local starred state of photos from
 * {@link android.provider.MediaStore.Images}.
 */
public class StarredDatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "IoGallery";
    private static final int DB_VERSION = 1;

    public static final String TABLE_STARRED = "starred";
    public static final String COLUMN_ID = BaseColumns._ID;
    public static final String COLUMN_STARRED = "starred";

    public StarredDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_STARRED + " ("
                + COLUMN_ID + " INTEGER PRIMARY KEY ON CONFLICT REPLACE, "
                + COLUMN_STARRED + " INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_STARRED);
        onCreate(db);
    }
}
