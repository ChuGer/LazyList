package com.fedorvlasov.lazylist;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.provider.ContactsContract;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class ImageLoader {

    private static final int THREAD_POOL_SIZE = 5;
    private static final int CONNECTION_TIMEOUT_MS = 30000;
    private final Pattern NUMBER_PATTERN = Pattern.compile("^[0-9]*$");
    private MemoryCache memoryCache = new MemoryCache();
    private FileCache fileCache;
    private Map<ImageView, String> imageViews = Collections.synchronizedMap(new WeakHashMap<ImageView, String>());
    private ExecutorService executorService;
    private int requiredSize = 70;
    private Context context;

    public ImageLoader(Context context) {
        this.context = context;
        fileCache = new FileCache(context);
        executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    final int stub_id = R.drawable.ic_action_person;

    public void displayImage(String url, ImageView imageView, Handler handler) {
        final ViewGroup.LayoutParams layoutParams = imageView.getLayoutParams();
        if (layoutParams != null && layoutParams.width > 0) {
            requiredSize = layoutParams.width;
        }
        imageViews.put(imageView, url);
        Bitmap bitmap = memoryCache.get(url);
        if (bitmap != null)
            imageView.setImageBitmap(bitmap);
        else {
            queuePhoto(url, imageView, handler);
            imageView.setImageResource(stub_id);
        }
    }

    private void queuePhoto(String url, ImageView imageView, Handler handler) {
        PhotoToLoad p = new PhotoToLoad(url, imageView);
        executorService.submit(new PhotosLoader(p, handler));
    }

    public Bitmap getBitmap(String url) {
        File f = fileCache.getFile(url);

        //from SD cache
        Bitmap b = decodeFile(f);
        if (b != null)
            return b;

        //from web or content provider
        try {
            Bitmap bitmap = null;
            if (NUMBER_PATTERN.matcher(url).matches()) {
                // PHOTO_ID from old API
                ContentResolver cr = context.getContentResolver();
                final Cursor c = cr.query(ContactsContract.Data.CONTENT_URI, new String[]{
                        ContactsContract.CommonDataKinds.Photo.PHOTO
                }, ContactsContract.Data._ID + "=?", new String[]{url}, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            byte[] imageBytes;
                            imageBytes = c.getBlob(0);
                            if (imageBytes != null) {
                                bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                            }
                        }
                        c.close();
                    } finally {
                        if (!c.isClosed()) {
                            c.close();
                        }
                    }
                }
            } else if (url.startsWith("content://")) {
                // PHOTO_URI from >= 11
                Uri contentURI = Uri.parse(url);
                InputStream in = context.getContentResolver().openInputStream(contentURI);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                bitmap = BitmapFactory.decodeStream(in, null, options);
            } else {
                URL imageUrl = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) imageUrl.openConnection();
                conn.setConnectTimeout(CONNECTION_TIMEOUT_MS);
                conn.setReadTimeout(CONNECTION_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
                InputStream is = conn.getInputStream();
                OutputStream os = new FileOutputStream(f);
                Utils.CopyStream(is, os);
                os.close();
                conn.disconnect();
                bitmap = decodeFile(f);
            }

            return bitmap;
        } catch (Throwable ex) {
            ex.printStackTrace();
            if (ex instanceof OutOfMemoryError)
                memoryCache.clear();
            return null;
        }
    }

    //decodes image and scales it to reduce memory consumption
    private Bitmap decodeFile(File f) {
        try {
            //decode image size
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            FileInputStream stream1 = new FileInputStream(f);
            BitmapFactory.decodeStream(stream1, null, o);
            stream1.close();

            //Find the correct scale value. It should be the power of 2.
            int width_tmp = o.outWidth, height_tmp = o.outHeight;
            int scale = 1;
            while (true) {
                if (width_tmp / 2 <= requiredSize || height_tmp / 2 <= requiredSize)
                    break;
                width_tmp /= 2;
                height_tmp /= 2;
                scale *= 2;
            }

            //decode with inSampleSize
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            FileInputStream stream2 = new FileInputStream(f);
            Bitmap bitmap = BitmapFactory.decodeStream(stream2, null, o2);
            stream2.close();
            return bitmap;
        } catch (FileNotFoundException ignored) {
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public String saveBitmapInFile(final Bitmap sourceBitmap, final int preferredSize) {
        final Bitmap bitmap;
        if (sourceBitmap.getWidth() > preferredSize) {
            bitmap = Bitmap.createScaledBitmap(sourceBitmap, preferredSize, preferredSize, false);
        } else {
            bitmap = sourceBitmap;
        }
        final String url = "userImage" + bitmap.hashCode();
        File f = fileCache.getFile(url);
        ByteArrayOutputStream bos = null;
        FileOutputStream fos = null;
        try {
            bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos);
            byte[] bitmapData = bos.toByteArray();
            fos = new FileOutputStream(f);
            fos.write(bitmapData);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
                if (bos != null) {
                    bos.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return url;
    }

    //Task for the queue
    private class PhotoToLoad {
        public String url;
        public ImageView imageView;

        public PhotoToLoad(String u, ImageView i) {
            url = u;
            imageView = i;
        }
    }

    class PhotosLoader implements Runnable {
        PhotoToLoad photoToLoad;
        private final Handler handler;

        PhotosLoader(PhotoToLoad photoToLoad, Handler handler) {
            this.photoToLoad = photoToLoad;
            this.handler = handler;
        }

        @Override
        public void run() {
            try {
                if (imageViewReused(photoToLoad))
                    return;
                Bitmap bmp = getBitmap(photoToLoad.url);
                memoryCache.put(photoToLoad.url, bmp);
                if (imageViewReused(photoToLoad))
                    return;
                BitmapDisplayer bd = new BitmapDisplayer(bmp, photoToLoad);
                this.handler.post(bd);
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    boolean imageViewReused(PhotoToLoad photoToLoad) {
        String tag = imageViews.get(photoToLoad.imageView);
        return tag == null || !tag.equals(photoToLoad.url);
    }

    //Used to display bitmap in the UI thread
    class BitmapDisplayer implements Runnable {
        Bitmap bitmap;
        PhotoToLoad photoToLoad;

        public BitmapDisplayer(Bitmap b, PhotoToLoad p) {
            bitmap = b;
            photoToLoad = p;
        }

        public void run() {
            if (imageViewReused(photoToLoad))
                return;
            if (bitmap != null)
                photoToLoad.imageView.setImageBitmap(bitmap);
            else
                photoToLoad.imageView.setImageResource(stub_id);
        }
    }

    public void clearCache() {
        memoryCache.clear();
        fileCache.clear();
    }

}
