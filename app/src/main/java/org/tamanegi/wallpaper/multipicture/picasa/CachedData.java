package org.tamanegi.wallpaper.multipicture.picasa;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.List;
import java.util.Random;

import org.tamanegi.wallpaper.multipicture.picasa.content.Entry;
import org.tamanegi.wallpaper.multipicture.picasa.content.Feed;
import org.tamanegi.wallpaper.multipicture.picasa.content.FeedResponse;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.os.Environment;
import android.provider.BaseColumns;

public class CachedData
{
    public static class ContentInfo
    {
        public String path;
        public String photo_id;
        public String url;
        public String name;
        public String timestamp;
        public int rotation;
    }

    private static final int CACHE_LIFETIME = (24 * 60 * 60 * 1000);
    private static final int MAX_CACHED_CONTENT = 50;
    private static final int BUFFER_SIZE = 1024 * 8;

    private static Object lock = new Object();

    private Context context;
    private GenericUrl[] urls;
    private String account_name;
    private OrderType order;
    private ContentInfo last_content;
    private int[] idx_list = null;
    private int cur_info_idx = -1;

    private Connection connection;
    private DataHelper helper;

    public CachedData(Context context,
                      GenericUrl[] urls, String account_name, OrderType order)
    {
        this.context = context;
        this.urls = urls;
        this.account_name = account_name;
        this.order = order;

        connection = new Connection(context);
        helper = new DataHelper(new DatabaseContext(context));
    }

    public void updatePhotoList(boolean follow_next)
    {
        synchronized(lock) {
            SQLiteDatabase db = helper.getWritableDatabase();

            try {
                for(GenericUrl url: urls) {
                    String location = url.build();

                    long last_update = getCacheLastUpdate(db, location, "");
                    long cur_time = System.currentTimeMillis();
                    if(last_update >= 0 &&
                       cur_time >= last_update &&
                       cur_time - last_update < CACHE_LIFETIME) {
                        continue;
                    }

                    String etag = getCacheEtag(db, location, "");

                    FeedResponse response = connection.executeGetFeed(
                        url, account_name, follow_next, etag);
                    if(response == null) {
                        continue;
                    }

                    db.beginTransaction();
                    try {
                        Feed feed = response.feed;
                        if(response.isNotModified()) {
                            updateCacheLastUpdate(
                                db, CacheInfoColumns.CACHE_TYPE_LIST,
                                location, "", etag);
                        }
                        else if(feed != null && feed.entries != null) {
                            updatePhotoList(db, location, feed, response.etag);
                        }

                        db.setTransactionSuccessful();
                    }
                    finally {
                        db.endTransaction();
                    }
                }

                deleteExpiredList(db);
            }
            finally {
                db.close();
            }
        }
    }

    public ContentInfo getCachedContent(boolean only_cached,
                                        List<String> last_urls)
    {
        synchronized(lock) {
            SQLiteDatabase db = helper.getWritableDatabase();

            db.beginTransaction();
            try {
                ContentInfo info =
                    getCachedContent(db, only_cached, true, last_urls);
                if(info == null) {
                    return null;
                }

                info.path = updateCachedContent(
                    db, info.url, info.timestamp, only_cached);
                if(info.path == null) {
                    return null;
                }

                db.setTransactionSuccessful();
                return info;
            }
            finally {
                db.endTransaction();
                db.close();
            }
        }
    }

    private long getCacheLastUpdate(SQLiteDatabase db,
                                    String location, String timestamp)
    {
        Cursor cur = db.query(
            CacheInfoColumns.TABLE_NAME,
            CacheInfoColumns.ALL_COLUMNS,
            CacheInfoColumns.LOCATION + " = ? AND " +
            CacheInfoColumns.ACCOUNT_NAME + " = ? AND " +
            CacheInfoColumns.TIMESTAMP + " = ?",
            new String[] { location, account_name, timestamp }, // WHERE
            null, null, null);
        try {
            if(cur.moveToFirst()) {
                return cur.getLong(CacheInfoColumns.COL_IDX_LAST_UPDATE);
            }
            else {
                return -1;
            }
        }
        finally {
            cur.close();
        }
    }

    private String getCacheEtag(SQLiteDatabase db,
                                String location, String timestamp)
    {
        Cursor cur = db.query(
            CacheInfoColumns.TABLE_NAME,
            CacheInfoColumns.ALL_COLUMNS,
            CacheInfoColumns.LOCATION + " = ? AND " +
            CacheInfoColumns.ACCOUNT_NAME + " = ? AND " +
            CacheInfoColumns.TIMESTAMP + " = ?",
            new String[] { location, account_name, timestamp }, // WHERE
            null, null, null);
        try {
            if(cur.moveToFirst()) {
                return cur.getString(CacheInfoColumns.COL_IDX_ETAG);
            }
            else {
                return null;
            }
        }
        finally {
            cur.close();
        }
    }

    private long updateCacheLastUpdate(SQLiteDatabase db, String type,
                                       String location, String timestamp,
                                       String etag)
    {
        db.delete(CacheInfoColumns.TABLE_NAME,
                  CacheInfoColumns.LOCATION + " = ? AND " +
                  CacheInfoColumns.ACCOUNT_NAME + " = ?",
                  new String[] { location, account_name });

        long cur_time = System.currentTimeMillis();

        ContentValues vals = new ContentValues();
        vals.put(CacheInfoColumns.TYPE, type);
        vals.put(CacheInfoColumns.LOCATION, location);
        vals.put(CacheInfoColumns.ACCOUNT_NAME, account_name);
        vals.put(CacheInfoColumns.TIMESTAMP, timestamp);
        vals.put(CacheInfoColumns.LAST_UPDATE, cur_time);
        vals.put(CacheInfoColumns.LAST_ACCESS, cur_time);
        vals.put(CacheInfoColumns.ETAG, etag);

        return db.insert(CacheInfoColumns.TABLE_NAME, "", vals);
    }

    private void updateCacheLastAccess(SQLiteDatabase db, String location)
    {
        long cur_time = System.currentTimeMillis();

        ContentValues vals = new ContentValues();
        vals.put(CacheInfoColumns.LAST_ACCESS, cur_time);

        db.update(CacheInfoColumns.TABLE_NAME, vals,
                  CacheInfoColumns.LOCATION + " = ? AND " +
                  CacheInfoColumns.ACCOUNT_NAME + " = ?",
                  new String[] { location, account_name });
    }

    private void updatePhotoList(SQLiteDatabase db, String location,
                                 Feed feed, String etag)
    {
        db.delete(AlbumColumns.TABLE_NAME,
                  AlbumColumns.LOCATION + " = ? AND " +
                  AlbumColumns.ACCOUNT_NAME + " = ?",
                  new String[] { location, account_name });

        for(Entry entry : feed.entries) {
            ContentValues vals = new ContentValues();
            vals.put(AlbumColumns.LOCATION, location);
            vals.put(AlbumColumns.ACCOUNT_NAME, account_name);
            vals.put(AlbumColumns.PHOTO_ID, entry.id);
            vals.put(AlbumColumns.CONTENT_URL, entry.getContentUrl());
            vals.put(AlbumColumns.CONTENT_NAME, entry.getTitle());
            vals.put(AlbumColumns.TIMESTAMP, parseLong(entry.timestamp));
            vals.put(AlbumColumns.ROTATION, parseInteger(entry.rotation));

            db.insert(AlbumColumns.TABLE_NAME, "", vals);
        }

        updateCacheLastUpdate(db, CacheInfoColumns.CACHE_TYPE_LIST,
                              location, "", etag);
    }

    private void deleteExpiredList(SQLiteDatabase db)
    {
        long cur_time = System.currentTimeMillis();
        String exp_time = String.valueOf(cur_time - CACHE_LIFETIME * 2);

        Cursor cur = db.query(
            CacheInfoColumns.TABLE_NAME,
            CacheInfoColumns.ALL_COLUMNS,
            CacheInfoColumns.TYPE + " = ? AND " +
            CacheInfoColumns.LAST_UPDATE + " <= ? AND " +
            CacheInfoColumns.LAST_ACCESS + " <= ?",
            new String[] { CacheInfoColumns.CACHE_TYPE_LIST,
                           exp_time, exp_time },
            null, null, null);
        try {
            while(cur.moveToNext()) {
                String location =
                    cur.getString(CacheInfoColumns.COL_IDX_LOCATION);
                String account =
                    cur.getString(CacheInfoColumns.COL_IDX_ACCOUNT_NAME);

                db.delete(AlbumColumns.TABLE_NAME,
                          AlbumColumns.LOCATION + " = ? AND " +
                          AlbumColumns.ACCOUNT_NAME + " = ?",
                          new String[] { location, account });
            }
        }
        finally {
            cur.close();
        }

        db.delete(CacheInfoColumns.TABLE_NAME,
                  CacheInfoColumns.TYPE + " = ? AND " +
                  CacheInfoColumns.LAST_UPDATE + " <= ? AND " +
                  CacheInfoColumns.LAST_ACCESS + " <= ?",
                  new String[] { CacheInfoColumns.CACHE_TYPE_LIST,
                                 exp_time, exp_time });

        // delete if unused file exist
        context.getDatabasePath(DataHelper.DB_NAME).delete();
    }

    private ContentInfo getCachedContent(SQLiteDatabase db,
                                         boolean only_cached, boolean use_last,
                                         List<String> last_urls)
    {
        use_last = (use_last && last_content != null);

        String[] locations = new String[urls.length];
        for(int i = 0; i < urls.length; i++) {
            locations[i] = urls[i].build();
        }

        String selection = null;
        String selection_arg = null;
        String order_by = null;

        if(order == OrderType.name_asc) {
            if(use_last) {
                selection =
                    CacheAlbumColumns.CONTENT_NAME + " > ? OR " +
                    "( " +
                    CacheAlbumColumns.CONTENT_NAME + " = ? AND " +
                    CacheAlbumColumns.PHOTO_ID + " > ?" +
                    " )";
                selection_arg = last_content.name;
            }
            order_by = CacheAlbumColumns.CONTENT_NAME + " ASC";
        }
        else if(order == OrderType.name_desc) {
            if(use_last) {
                selection =
                    CacheAlbumColumns.CONTENT_NAME + " < ? OR " +
                    "( " +
                    CacheAlbumColumns.CONTENT_NAME + " = ? AND " +
                    CacheAlbumColumns.PHOTO_ID + " > ?" +
                    " )";
                selection_arg = last_content.name;
            }
            order_by = CacheAlbumColumns.CONTENT_NAME + " DESC";
        }
        else if(order == OrderType.date_asc) {
            if(use_last) {
                selection =
                    CacheAlbumColumns.TIMESTAMP + " > ? OR " +
                    "( " +
                    CacheAlbumColumns.TIMESTAMP + " = ? AND " +
                    CacheAlbumColumns.PHOTO_ID + " > ?" +
                    " )";
                selection_arg = last_content.timestamp;
            }
            order_by = CacheAlbumColumns.TIMESTAMP + " ASC";
        }
        else if(order == OrderType.date_desc) {
            if(use_last) {
                selection =
                    CacheAlbumColumns.TIMESTAMP + " < ? OR " +
                    "( " +
                    CacheAlbumColumns.TIMESTAMP + " = ? AND " +
                    CacheAlbumColumns.PHOTO_ID + " > ?" +
                    " )";
                selection_arg = last_content.timestamp;
            }
            order_by = CacheAlbumColumns.TIMESTAMP + " DESC";
        }
        else {
            use_last = false;
        }

        StringBuilder where = new StringBuilder();
        String[] where_args = new String[locations.length + 1 +
                                         (selection != null ? 3 : 0)];
        for(int i = 0; i < locations.length; i++) {
            where
                .append(i == 0 ? "( " : " OR ")
                .append(CacheAlbumColumns.LOCATION)
                .append(" = ?");
            where_args[i] = locations[i];
        }
        where.append(" )");

        where_args[locations.length + 0] = account_name;
        if(selection != null) {
            where_args[locations.length + 1] = selection_arg;
            where_args[locations.length + 2] = selection_arg;
            where_args[locations.length + 3] = last_content.photo_id;
        }

        where.append(" AND ")
            .append(CacheAlbumColumns.ACCOUNT_NAME).append(" = ?");
        if(only_cached) {
            where.append(" AND ")
                .append(CacheAlbumColumns.RIGHT_TABLE_NAME).append(".")
                .append(AlbumColumns.LOCATION).append(" IS NOT NULL");
        }
        if(selection != null) {
            where.append(" AND ")
                .append("( ").append(selection).append(" )");
        }

        String where_str = where.toString();

        int count = 1;
        if(order == OrderType.random) {
            Cursor cur = db.query(
                CacheAlbumColumns.TABLE_NAME,
                new String[] { "COUNT(*) AS COUNT" },
                where_str, where_args,          // WHERE
                null, null,                     // GROUP BY, HAVING
                null);                          // ORDER BY
            try {
                if(cur.moveToFirst()) {
                    count = cur.getInt(0);
                }
            }
            finally {
                cur.close();
            }

            // prepare index list
            if(idx_list == null || idx_list.length != cur.getCount()) {
                idx_list = new int[count];

                for(int i = 0; i < count; i++) {
                    idx_list[i] = i;
                }

                cur_info_idx = -1;
            }
        }

        String limit = "1";
        int retry_saved_idx = -1;
        ContentInfo retry_saved_info = null;

        for(int i = 0; i < count; i++) {
            int next_idx = (cur_info_idx + i + 1) % count;
            if(order == OrderType.random) {
                if(next_idx == 0) {
                    shuffleIndexes();
                }

                limit = idx_list[next_idx] + ", 1";
            }

            Cursor cur = db.query(
                CacheAlbumColumns.TABLE_NAME,
                CacheAlbumColumns.ALL_COLUMNS,
                where_str, where_args,              // WHERE
                null, null,                         // GROUP BY, HAVING
                (order_by != null ? order_by + ", " : "") +
                CacheAlbumColumns.PHOTO_ID + " ASC", // ORDER BY
                limit);                              // LIMIT
            try {
                if(! cur.moveToFirst()) {
                    if(use_last) {
                        return getCachedContent(
                            db, only_cached, false, last_urls);
                    }
                    else {
                        return null;
                    }
                }

                ContentInfo info = new ContentInfo();
                info.photo_id =
                    cur.getString(CacheAlbumColumns.COL_IDX_PHOTO_ID);
                info.url =
                    cur.getString(CacheAlbumColumns.COL_IDX_CONTENT_URL);
                info.name =
                    cur.getString(CacheAlbumColumns.COL_IDX_CONTENT_NAME);
                info.timestamp =
                    cur.getString(CacheAlbumColumns.COL_IDX_TIMESTAMP);
                info.rotation =
                    cur.getInt(CacheAlbumColumns.COL_IDX_ROTATION);

                if(order == OrderType.random && last_urls.contains(info.url)) {
                    if(i == 0) {
                        retry_saved_info = info;
                        retry_saved_idx = next_idx;
                    }
                    continue;
                }

                cur_info_idx = next_idx;
                last_content = info;
                return info;
            }
            finally {
                cur.close();
            }
        }

        if(retry_saved_info != null) {
            cur_info_idx = retry_saved_idx;
            last_content = retry_saved_info;
            return retry_saved_info;
        }

        return null;
    }

    private String updateCachedContent(SQLiteDatabase db,
                                       String url, String timestamp,
                                       boolean only_cached)
    {
        // get cached file if exists
        Cursor cur = db.query(
            CacheInfoColumns.TABLE_NAME,
            CacheInfoColumns.ALL_COLUMNS,
            CacheInfoColumns.LOCATION + " = ? AND " +
            CacheInfoColumns.ACCOUNT_NAME + " = ? AND " +
            CacheInfoColumns.TIMESTAMP + " = ?",
            new String[] { url, account_name, timestamp }, // WHERE
            null, null, null);
        try {
            if(cur.moveToFirst()) {
                String id = cur.getString(CacheInfoColumns.COL_IDX_ID);
                File file = getCacheFile(id, url);
                if(file != null && file.isFile()) {
                    updateCacheLastAccess(db, url);
                    return file.getAbsolutePath();
                }
            }
        }
        finally {
            cur.close();
        }
        if(only_cached) {
            return null;
        }

        // delete old files
        adjustCachedContentCount(db);

        // regist id for file name
        long id = updateCacheLastUpdate(db, CacheInfoColumns.CACHE_TYPE_CONTENT,
                                        url, timestamp, null);
        if(id < 0) {
            return null;
        }

        File file = getCacheFile(String.valueOf(id), url);
        if(file == null) {
            return null;
        }

        // get content
        HttpResponse response =
            connection.executeGet(new GenericUrl(url), account_name, null);
        if(response == null) {
            return null;
        }

        // write to cache file
        InputStream in = null;
        BufferedOutputStream out = null;
        try {
            try {
                response.setDisableContentLogging(true);
                in = response.getContent();
                out = new BufferedOutputStream(new FileOutputStream(file),
                                               BUFFER_SIZE);

                byte[] buf = new byte[BUFFER_SIZE];
                while(true) {
                    int size = in.read(buf);
                    if(size < 0) {
                        break;
                    }

                    out.write(buf, 0, size);
                }
            }
            finally {
                if(in != null) {
                    in.close();
                }
                else {
                    response.ignore();
                }

                if(out != null) {
                    out.close();
                }
            }
        }
        catch(IOException e) {
            e.printStackTrace();
            file.delete();
            return null;
        }

        return file.getAbsolutePath();
    }

    private void adjustCachedContentCount(SQLiteDatabase db)
    {
        Cursor cur = db.query(
            CacheInfoColumns.TABLE_NAME,
            CacheInfoColumns.ALL_COLUMNS,
            CacheInfoColumns.TYPE + " = ?",
            new String[] { CacheInfoColumns.CACHE_TYPE_CONTENT }, // WHERE
            null, null,
            CacheInfoColumns.LAST_ACCESS + " ASC");
        try {
            int cnt = cur.getCount();
            if(cnt < MAX_CACHED_CONTENT) {
                return;
            }

            for(int i = 0; i <= cnt - MAX_CACHED_CONTENT; i++) {
                cur.moveToNext();

                String id = cur.getString(CacheInfoColumns.COL_IDX_ID);
                String url = cur.getString(CacheInfoColumns.COL_IDX_LOCATION);
                File file = getCacheFile(id, url);
                if(file != null) {
                    file.delete();
                }

                db.delete(CacheInfoColumns.TABLE_NAME,
                          CacheInfoColumns._ID + " = ?",
                          new String[] { id });
            }
        }
        finally {
            cur.close();
        }
    }

    private File getCacheFile(String id, String url)
    {
        File base_dir = null;

        try {
            Method method = context.getClass().getMethod("getExternalCacheDir");
            base_dir = (File)method.invoke(context);
        }
        catch(Exception e) {
            File ext_dir = Environment.getExternalStorageDirectory();
            if(ext_dir != null) {
                base_dir = new File(ext_dir,
                                    "Android" + File.separator +
                                    "data" + File.separator +
                                    context.getPackageName() + File.separator +
                                    "cache");
            }
        }

        if(base_dir == null) {
            return null;
        }

        base_dir.mkdirs();

        StringBuilder name = new StringBuilder();
        name.append(id).append("_");

        try {
            byte[] digest =
                MessageDigest.getInstance("MD5").digest(url.getBytes("UTF-8"));
            for(byte b : digest) {
                name.append(Integer.toHexString((b >> 4) & 0xf))
                    .append(Integer.toHexString(b & 0xf));
            }
        }
        catch(Exception e) {
            // ignore
        }

        return new File(base_dir, name.toString());
    }

    private Integer parseInteger(String val)
    {
        try {
            return new Integer(val);
        }
        catch(Exception e) {
            return Integer.valueOf(0);
        }
    }

    private Long parseLong(String val)
    {
        try {
            return new Long(val);
        }
        catch(Exception e) {
            return Long.valueOf(0);
        }
    }

    private void shuffleIndexes()
    {
        Random random = new Random();

        for(int i = 0; i < idx_list.length; i++) {
            int idx = i + random.nextInt(idx_list.length - i);
            int v = idx_list[i];
            idx_list[i] = idx_list[idx];
            idx_list[idx] = v;
        }
    }

    private static interface CacheInfoColumns extends BaseColumns
    {
        public static final String TABLE_NAME = "cache_info";

        public static final String TYPE = "type";
        public static final String LOCATION = "location";
        public static final String ACCOUNT_NAME = "account";
        public static final String TIMESTAMP = "timestamp";
        public static final String LAST_UPDATE = "last_update";
        public static final String LAST_ACCESS = "last_access";
        public static final String ETAG = "etag";

        public static final String[] ALL_COLUMNS = {
            _ID, LOCATION, ACCOUNT_NAME, LAST_UPDATE, LAST_ACCESS, ETAG
        };
        public static final int COL_IDX_ID = 0;
        public static final int COL_IDX_LOCATION = 1;
        public static final int COL_IDX_ACCOUNT_NAME = 2;
        public static final int COL_IDX_LAST_UPDATE = 3;
        public static final int COL_IDX_ETAG = 5;

        public static final String CACHE_TYPE_LIST = "list";
        public static final String CACHE_TYPE_CONTENT = "content";
    }

    private static interface AlbumColumns extends BaseColumns
    {
        public static final String TABLE_NAME = "album";

        public static final String LOCATION = "location";
        public static final String ACCOUNT_NAME = "account";
        public static final String PHOTO_ID = "photo_id";
        public static final String CONTENT_URL = "content_url";
        public static final String CONTENT_NAME = "content_name";
        public static final String TIMESTAMP = "timestamp";
        public static final String ROTATION = "rotation";
    }

    private static interface CacheAlbumColumns extends AlbumColumns
    {
        public static final String LEFT_TABLE_NAME =
            AlbumColumns.TABLE_NAME;
        public static final String RIGHT_TABLE_NAME =
            CacheInfoColumns.TABLE_NAME;

        public static final String TABLE_NAME =
            LEFT_TABLE_NAME + " LEFT OUTER JOIN " +
            RIGHT_TABLE_NAME +
            " ON " +
            LEFT_TABLE_NAME + "." + AlbumColumns.CONTENT_URL + " = " +
            RIGHT_TABLE_NAME + "." + CacheInfoColumns.LOCATION +
            " AND " +
            LEFT_TABLE_NAME + "." + AlbumColumns.ACCOUNT_NAME + " = " +
            RIGHT_TABLE_NAME + "." + CacheInfoColumns.ACCOUNT_NAME +
            " AND " +
            LEFT_TABLE_NAME + "." + AlbumColumns.TIMESTAMP + " = " +
            RIGHT_TABLE_NAME + "." + CacheInfoColumns.TIMESTAMP;

        public static final String _ID =
            LEFT_TABLE_NAME + "." + AlbumColumns._ID;
        public static final String LOCATION =
            LEFT_TABLE_NAME + "." + AlbumColumns.LOCATION;
        public static final String ACCOUNT_NAME =
            LEFT_TABLE_NAME + "." + AlbumColumns.ACCOUNT_NAME;
        public static final String PHOTO_ID =
            LEFT_TABLE_NAME + "." + AlbumColumns.PHOTO_ID;
        public static final String CONTENT_URL =
            LEFT_TABLE_NAME + "." + AlbumColumns.CONTENT_URL;
        public static final String CONTENT_NAME =
            LEFT_TABLE_NAME + "." + AlbumColumns.CONTENT_NAME;
        public static final String TIMESTAMP =
            LEFT_TABLE_NAME + "." + AlbumColumns.TIMESTAMP;
        public static final String ROTATION =
            LEFT_TABLE_NAME + "." + AlbumColumns.ROTATION;

        public static final String[] ALL_COLUMNS = {
            _ID, LOCATION, ACCOUNT_NAME, PHOTO_ID,
            CONTENT_URL, CONTENT_NAME, TIMESTAMP, ROTATION
        };
        public static final int COL_IDX_PHOTO_ID = 3;
        public static final int COL_IDX_CONTENT_URL = 4;
        public static final int COL_IDX_CONTENT_NAME = 5;
        public static final int COL_IDX_TIMESTAMP = 6;
        public static final int COL_IDX_ROTATION = 7;
    }

    private static class DataHelper extends SQLiteOpenHelper
    {
        private static final String DB_NAME = "list.db";

        private static final int DB_VERSION_V1 = 1;
        private static final int DB_VERSION_V2 = 2;
        private static final int DB_VERSION = DB_VERSION_V2;

        private DataHelper(Context context)
        {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db)
        {
            db.execSQL(
                "CREATE TABLE " + CacheInfoColumns.TABLE_NAME + " (" +
                CacheInfoColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                CacheInfoColumns.TYPE + " TEXT," +
                CacheInfoColumns.LOCATION + " TEXT," +
                CacheInfoColumns.ACCOUNT_NAME + " TEXT," +
                CacheInfoColumns.TIMESTAMP + " TEXT," +
                CacheInfoColumns.LAST_UPDATE + " INTEGER," +
                CacheInfoColumns.LAST_ACCESS + " INTEGER," +
                CacheInfoColumns.ETAG + " TEXT" +
                ");");

            db.execSQL(
                "CREATE TABLE " + AlbumColumns.TABLE_NAME + " (" +
                AlbumColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                AlbumColumns.LOCATION + " TEXT," +
                AlbumColumns.ACCOUNT_NAME + " TEXT," +
                AlbumColumns.PHOTO_ID + " TEXT," +
                AlbumColumns.CONTENT_URL + " TEXT," +
                AlbumColumns.CONTENT_NAME + " TEXT," +
                AlbumColumns.TIMESTAMP + " INTEGER," +
                AlbumColumns.ROTATION + " INTEGER" +
                ");");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
        {
            if(oldVersion == DB_VERSION_V1) {
                db.execSQL(
                    "ALTER TABLE " + CacheInfoColumns.TABLE_NAME +
                    " ADD COLUMN " + CacheInfoColumns.ETAG);
            }
            else {
                db.execSQL(
                    "DROP TABLE IF EXISTS " + CacheInfoColumns.TABLE_NAME);
                db.execSQL(
                    "DROP TABLE IF EXISTS " + AlbumColumns.TABLE_NAME);
                onCreate(db);
            }
        }
    }

    private static class DatabaseContext extends ContextWrapper
    {
        private DatabaseContext(Context context)
        {
            super(context);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(
            String name, int mode, CursorFactory factory)
        {
            String path = getDatabasePathString(name);
            return SQLiteDatabase.openOrCreateDatabase(path, factory);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(
            String name, int mode, CursorFactory factory,
            DatabaseErrorHandler ehandler)
        {
            String path = getDatabasePathString(name);
            return SQLiteDatabase.openOrCreateDatabase(path, factory, ehandler);
        }

        public boolean deleteDatabase(String name)
        {
            return super.deleteDatabase(getDatabasePathString(name));
        }

        @Override
        public File getDatabasePath(String name)
        {
            return new File(getCacheDir(), name);
        }

        private String getDatabasePathString(String name)
        {
            return getDatabasePath(name).getAbsolutePath();
        }
    }
}
