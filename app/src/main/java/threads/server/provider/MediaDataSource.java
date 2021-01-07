package threads.server.provider;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import androidx.annotation.NonNull;

import java.io.IOException;

import lite.Reader;
import threads.LogUtils;
import threads.server.ipfs.CID;
import threads.server.ipfs.IPFS;

public class MediaDataSource extends android.media.MediaDataSource {
    private static final String TAG = MediaDataSource.class.getSimpleName();
    private Reader fileReader;


    public MediaDataSource(@NonNull IPFS ipfs, @NonNull CID cid) throws Exception {
        this.fileReader = ipfs.getReader(cid);
    }

    public static Bitmap getVideoFrame(@NonNull Context context, @NonNull CID cid, long time) {

        Bitmap bitmap = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {

            retriever.setDataSource(new MediaDataSource(IPFS.getInstance(context), cid));

            if (time <= 0) {
                return retriever.getFrameAtTime();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                bitmap = retriever.getScaledFrameAtTime(time * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256);
            } else {
                bitmap = retriever.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }

        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);

        } finally {

            try {

                retriever.release();

            } catch (RuntimeException throwable) {
                LogUtils.error(TAG, throwable);
            }
        }

        return bitmap;
    }

    @Override
    public void close() throws IOException {

        try {
            if (fileReader != null) {
                fileReader.close();
            }
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            fileReader = null;
        }
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        try {
            fileReader.readAt(position, size);
            long read = fileReader.getRead();
            if (read > 0) {
                byte[] data = fileReader.getData();
                System.arraycopy(data, 0, buffer, offset, data.length);
            }
            return (int) read;
        } catch (Throwable throwable) {
            throw new IOException(throwable);
        }

    }

    @Override
    public long getSize() {
        return fileReader.getSize();
    }
}