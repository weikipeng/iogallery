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
import android.app.ActivityManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView.MultiChoiceModeListener;
import android.widget.AbsListView.RecyclerListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.CursorAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.example.android.util.LongSparseBooleanArray;
import com.example.android.util.LruCache;

/**
 * List of photos on device, which may be cached as user scrolls.
 */
public class AlbumActivity extends Activity {
    private static final String TAG = "IoGallery";
    
    // TODO: place LruCache into a Loader, onRetain(), or static to keep across config changes

    private static final int LOADER_CURSOR = 1;
    private static final int LOADER_STARRED = 2;

    private StarredDatabaseHelper mStarredDb;

    private ThumbnailCache mCache;
    private boolean mCacheEnabled;
    private boolean mTransactionEnabled;
    
    private PhotoAdapter mAdapter;
    private GridView mGridView;

    private View mStats;
    private TextView mStatsSize;
    private TextView mStatsHits;
    private TextView mStatsMisses;
    private TextView mStatsEvictions;

    /**
     * Adapter showing list of photos from
     * {@link android.provider.MediaStore.Images}.
     */
    private class PhotoAdapter extends CursorAdapter {
        private LongSparseBooleanArray mStarred;
        
        public PhotoAdapter(Context context) {
            super(context, null, false);
        }

        public void swapStarred(LongSparseBooleanArray starred) {
            mStarred = starred;
            notifyDataSetChanged();
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return LayoutInflater.from(context).inflate(R.layout.album_item, parent, false);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            final long photoId = cursor.getLong(cursor.getColumnIndex(BaseColumns._ID));
            final boolean starred = (mStarred != null && mStarred.get(photoId));

            final ImageView imageView = (ImageView) view.findViewById(android.R.id.icon);
            final View starredView = view.findViewById(android.R.id.checkbox);
            starredView.setVisibility(starred ? View.VISIBLE : View.GONE);

            // Cancel any pending thumbnail task, since this view is now bound
            // to new thumbnail
            final ThumbnailAsyncTask oldTask = (ThumbnailAsyncTask) imageView.getTag();
            if (oldTask != null) {
                oldTask.cancel(false);
            }

            if (mCacheEnabled) {
                // Cache enabled, try looking for cache hit
                final Bitmap cachedResult = mCache.get(photoId);
                if (cachedResult != null) {
                    imageView.setImageBitmap(cachedResult);
                    updateCacheStatsUi();
                    return;
                }
            }

            // If we arrived here, either cache is disabled or cache miss, so we
            // need to kick task to load manually
            final ThumbnailAsyncTask task = new ThumbnailAsyncTask(imageView);
            imageView.setImageBitmap(null);
            imageView.setTag(task);
            task.execute(photoId);
        }
    }

    public class ThumbnailAsyncTask extends AsyncTask<Long, Void, Bitmap> {
        private final ImageView mTarget;

        public ThumbnailAsyncTask(ImageView target) {
            mTarget = target;
        }

        @Override
        protected void onPreExecute() {
            mTarget.setTag(this);
        }

        @Override
        protected Bitmap doInBackground(Long... params) {
            final long id = params[0];

            final Bitmap result = MediaStore.Images.Thumbnails.getThumbnail(
                    getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);

            // When cache enabled, keep reference to this bitmap
            if (mCacheEnabled) {
                mCache.put(id, result);
            }

            return result;
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            if (mTarget.getTag() == this) {
                mTarget.setImageBitmap(result);
                mTarget.setTag(null);
                updateCacheStatsUi();
            }
        }        
    }

    /**
     * Task that saves starred state to {@link StarredDatabaseHelper} for given
     * {@link BaseColumns#_ID}.
     */
    public class StarredPersistTask extends AsyncTask<Void, Void, Void> {
        private final long[] mPhotoIds;
        private final boolean mStarred;

        public StarredPersistTask(long[] photoIds, boolean starred) {
            mPhotoIds = photoIds;
            mStarred = starred;
        }

        @Override
        protected Void doInBackground(Void... params) {
            final SQLiteDatabase db = mStarredDb.getWritableDatabase();

            final ContentValues values = new ContentValues();
            values.put(StarredDatabaseHelper.COLUMN_STARRED, mStarred ? 1 : 0);

            final long start = System.nanoTime();

            if (mTransactionEnabled) db.beginTransaction();
            try {
                for (long photoId : mPhotoIds) {
                    values.put(StarredDatabaseHelper.COLUMN_ID, photoId);
                    db.insert(StarredDatabaseHelper.TABLE_STARRED, null, values);
                }
                if (mTransactionEnabled) db.setTransactionSuccessful();
            } finally {
                if (mTransactionEnabled) db.endTransaction();
            }

            final long delta = System.nanoTime() - start;
            Log.d(TAG, "Persisting took " + (delta / 1000000) + "ms");

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            // Reload starred data to reflect newly persisted data
            getLoaderManager().restartLoader(LOADER_STARRED, null, mStarredCallbacks);
        }        
    }

    /**
     * Update UI that shows cache statistics.
     */
    private void updateCacheStatsUi() {
        mStatsSize.setText(Formatter.formatFileSize(this, mCache.size()));
        mStatsHits.setText(Integer.toString(mCache.hitCount()));
        mStatsMisses.setText(Integer.toString(mCache.missCount()));
        mStatsEvictions.setText(Integer.toString(mCache.evictionCount()));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mStarredDb = new StarredDatabaseHelper(this);

        setContentView(R.layout.album);

        mStats = findViewById(R.id.stats);
        mStatsSize = (TextView) findViewById(R.id.stats_size);
        mStatsHits = (TextView) findViewById(R.id.stats_hits);
        mStatsMisses = (TextView) findViewById(R.id.stats_misses);
        mStatsEvictions = (TextView) findViewById(R.id.stats_evictions);

        // Pick cache size based on memory class of device
        final ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        final int memoryClassBytes = am.getMemoryClass() * 1024 * 1024;
        mCache = new ThumbnailCache(memoryClassBytes / 2);

        mAdapter = new PhotoAdapter(this);

        mGridView = (GridView) findViewById(android.R.id.list);
        mGridView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        mGridView.setMultiChoiceModeListener(mStarredListener);
        mGridView.setAdapter(mAdapter);

        mGridView.setRecyclerListener(new RecyclerListener() {
            @Override
            public void onMovedToScrapHeap(View view) {
                // Release strong reference when a view is recycled
                final ImageView imageView = (ImageView) view.findViewById(android.R.id.icon);
                imageView.setImageBitmap(null);
            }
        });

        mGridView.setOnItemClickListener(mPhotoClickListener);

        // Kick off loader for Cursor with list of photos
        getLoaderManager().initLoader(LOADER_CURSOR, null, mCursorCallbacks);
        getLoaderManager().initLoader(LOADER_STARRED, null, mStarredCallbacks);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateCacheStatsUi();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mAdapter.swapCursor(null);
    }

    private MultiChoiceModeListener mStarredListener = new MultiChoiceModeListener() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            getMenuInflater().inflate(R.menu.album_star, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.menu_star: {
                    new StarredPersistTask(mGridView.getCheckedItemIds(), true).execute();
                    mode.finish();
                    return true;
                }
                case R.id.menu_star_off: {
                    new StarredPersistTask(mGridView.getCheckedItemIds(), false).execute();
                    mode.finish();
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            // Ignored
        }

        @Override
        public void onItemCheckedStateChanged(
                ActionMode mode, int position, long id, boolean checked) {
            final int count = mGridView.getCheckedItemCount();
            mode.setTitle(getResources().getQuantityString(R.plurals.selected_count, count, count));
        }
    };

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.v(TAG, "onTrimMemory() with level=" + level);

        // Memory we can release here will help overall system performance, and
        // make us a smaller target as the system looks for memory

        if (level >= TRIM_MEMORY_MODERATE) { // 60
            // Nearing middle of list of cached background apps; evict our
            // entire thumbnail cache
            Log.v(TAG, "evicting entire thumbnail cache");
            mCache.evictAll();

        } else if (level >= TRIM_MEMORY_BACKGROUND) { // 40
            // Entering list of cached background apps; evict oldest half of our
            // thumbnail cache
            Log.v(TAG, "evicting oldest half of thumbnail cache");
            mCache.trimToSize(mCache.size() / 2);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.album, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_cache: {
                mCacheEnabled = !item.isChecked();
                item.setChecked(mCacheEnabled);
                mCache.evictAll();
                mStats.setVisibility(mCacheEnabled ? View.VISIBLE : View.GONE);
                return true;
            }
            case R.id.menu_transaction: {
                mTransactionEnabled = !item.isChecked();
                item.setChecked(mTransactionEnabled);
                return true;
            }
        }
        return false;
    }

    private OnItemClickListener mPhotoClickListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            // User clicked on photo, open our viewer
            final Intent intent = new Intent(AlbumActivity.this, PhotoActivity.class);
            final Uri data = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            intent.setData(data);
            startActivity(intent);
        }
    };

    private final LoaderCallbacks<Cursor> mCursorCallbacks = new LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            final String[] columns = { BaseColumns._ID };
            return new CursorLoader(AlbumActivity.this,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC");
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            mAdapter.swapCursor(data);
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            mAdapter.swapCursor(null);
        }
    };

    private final LoaderCallbacks<LongSparseBooleanArray>
            mStarredCallbacks = new LoaderCallbacks<LongSparseBooleanArray>() {
        @Override
        public Loader<LongSparseBooleanArray> onCreateLoader(int id, Bundle args) {
            return new StarredLoader(AlbumActivity.this, mStarredDb);
        }

        @Override
        public void onLoadFinished(
                Loader<LongSparseBooleanArray> loader, LongSparseBooleanArray data) {
            mAdapter.swapStarred(data);
        }

        @Override
        public void onLoaderReset(Loader<LongSparseBooleanArray> loader) {
            mAdapter.swapStarred(null);
        }
    };

    /**
     * Simple extension that uses {@link Bitmap} instances as keys, using their
     * memory footprint in bytes for sizing.
     */
    public static class ThumbnailCache extends LruCache<Long, Bitmap> {
        public ThumbnailCache(int maxSizeBytes) {
            super(maxSizeBytes);
        }
        
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    }
}
