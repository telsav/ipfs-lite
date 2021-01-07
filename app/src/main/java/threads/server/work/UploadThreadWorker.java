package threads.server.work;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import threads.LogUtils;
import threads.server.InitApplication;
import threads.server.MainActivity;
import threads.server.R;
import threads.server.core.DOCS;
import threads.server.core.peers.Content;
import threads.server.core.threads.THREADS;
import threads.server.core.threads.Thread;
import threads.server.ipfs.CID;
import threads.server.ipfs.IPFS;
import threads.server.ipfs.LinkInfo;
import threads.server.ipfs.Progress;
import threads.server.magic.ContentInfo;
import threads.server.magic.ContentInfoUtil;
import threads.server.provider.FileDocumentsProvider;
import threads.server.provider.FileProvider;
import threads.server.services.ConnectService;
import threads.server.services.LiteService;
import threads.server.utils.MimeType;

public class UploadThreadWorker extends Worker {

    private static final String TAG = UploadThreadWorker.class.getSimpleName();

    private final DOCS docs;
    private final THREADS threads;
    private final IPFS ipfs;
    private final NotificationManager mNotificationManager;
    private final AtomicReference<Notification> mLastNotification = new AtomicReference<>(null);
    private final Object lock = new Object();
    private final AtomicBoolean finished = new AtomicBoolean(true);
    private long idx;

    @SuppressWarnings("WeakerAccess")
    public UploadThreadWorker(
            @NonNull Context context,
            @NonNull WorkerParameters params) {
        super(context, params);
        docs = DOCS.getInstance(context);
        threads = THREADS.getInstance(context);
        ipfs = IPFS.getInstance(context);
        mNotificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
    }


    public static OneTimeWorkRequest getSharedWork() {
        return new OneTimeWorkRequest.Builder(UploadThreadWorker.class)
                .addTag(TAG)
                .setInitialDelay(1, TimeUnit.MILLISECONDS)
                .build();
    }


    public static OneTimeWorkRequest getWork(long idx, boolean bootstrap) {

        Data.Builder data = new Data.Builder();
        data.putLong(Content.IDX, idx);
        data.putBoolean(Content.BOOT, bootstrap);

        return new OneTimeWorkRequest.Builder(UploadThreadWorker.class)
                .addTag(TAG)
                .setInputData(data.build())
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public static UUID load(@NonNull Context context, long idx, boolean bootstrap) {
        OneTimeWorkRequest request = getWork(idx, bootstrap);
        WorkManager.getInstance(context).enqueue(request);
        return request.getId();
    }


    @NonNull
    private ForegroundInfo createForegroundInfo(@NonNull String title, int progress) {
        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = createNotification(title, progress);
        } else {
            notification = createCompatNotification(title, progress);
        }
        mLastNotification.set(notification);
        return new ForegroundInfo((int) idx, notification);
    }

    @NonNull
    @Override
    public Result doWork() {


        idx = getInputData().getLong(Content.IDX, -1);

        long start = System.currentTimeMillis();
        LogUtils.info(TAG, " start ... " + idx);
        boolean bootstrap = getInputData().getBoolean(Content.BOOT, true);


        try {
            IPFS ipfs = IPFS.getInstance(getApplicationContext());
            THREADS threads = THREADS.getInstance(getApplicationContext());

            Thread thread = threads.getThreadByIdx(idx);
            Objects.requireNonNull(thread);


            if (!threads.isThreadLeaching(idx)) {
                threads.setThreadLeaching(idx);
            }

            if (threads.isThreadInit(idx)) {
                threads.resetThreadInit(idx);
            }

            if (!Objects.equals(thread.getWorkUUID(), getId())) {
                threads.setThreadWork(idx, getId());
            }

            String url = thread.getUri();
            Objects.requireNonNull(url);
            Uri uri = Uri.parse(url);

            if (Objects.equals(uri.getScheme(), Content.HTTPS) ||
                    Objects.equals(uri.getScheme(), Content.HTTP) ||
                    Objects.equals(uri.getScheme(), Content.IPFS) ||
                    Objects.equals(uri.getScheme(), Content.IPNS)) {
                ForegroundInfo foregroundInfo = createForegroundInfo(
                        thread.getName(), thread.getProgress());
                setForegroundAsync(foregroundInfo);
            }

            if (Objects.equals(uri.getScheme(), Content.IPFS) ||
                    Objects.equals(uri.getScheme(), Content.IPNS)) {
                if (bootstrap) {
                    if (!isStopped()) {
                        try {
                            LiteService.bootstrap(getApplicationContext(), 10, false);

                            ConnectService.connect(getApplicationContext());
                        } catch (Throwable e) {
                            LogUtils.error(TAG, e);
                        }
                    }
                }
            }

            if (Objects.equals(uri.getScheme(), Content.HTTPS) ||
                    Objects.equals(uri.getScheme(), Content.HTTP)) {
                try {
                    String filename = thread.getName();
                    Objects.requireNonNull(filename);


                    URL u = new URL(uri.toString());
                    HttpURLConnection huc = (HttpURLConnection) u.openConnection();
                    HttpURLConnection.setFollowRedirects(false);
                    huc.setReadTimeout(30 * 1000);
                    huc.connect();

                    final long size = thread.getSize();

                    InputStream is = huc.getInputStream();

                    CID cid = ipfs.storeInputStream(is, new Progress() {


                        @Override
                        public void setProgress(int percent) {
                            threads.setThreadProgress(idx, percent);
                            reportProgress(filename, percent);
                        }

                        @Override
                        public boolean doProgress() {
                            return !isStopped();
                        }

                        @Override
                        public boolean isClosed() {
                            return isStopped();
                        }


                    }, size);


                    if (cid != null) {
                        threads.setThreadDone(idx, cid);
                        Uri newUri = FileDocumentsProvider.getUriForThread(idx);
                        threads.setThreadUri(idx, newUri.toString());
                    } else {
                        threads.resetThreadLeaching(idx);
                    }

                } catch (Throwable e) {
                    if (!isStopped()) {
                        threads.setThreadError(idx);
                        buildFailedNotification((int) idx, thread.getName());
                    }
                    throw e;
                } finally {
                    if (threads.isThreadLeaching(idx)) {
                        threads.resetThreadLeaching(idx);
                    }
                    threads.resetThreadWork(idx);
                }
            } else if (Objects.equals(uri.getScheme(), Content.IPNS) ||
                    Objects.equals(uri.getScheme(), Content.IPFS)) {

                try {

                    if (threads.getThreadContent(idx) == null) {
                        DOCS.FileInfo fileInfo = docs.getFileInfo(uri, this::isStopped);


                        String name = fileInfo.getFilename();
                        List<Thread> names = threads.getThreadsByNameAndParent(
                                ipfs.getLocation(), name, 0L);
                        names.remove(thread);
                        if (!names.isEmpty()) {
                            name = docs.getUniqueName(name, 0L);
                        }
                        threads.setThreadName(idx, name);
                        threads.setThreadMimeType(idx, fileInfo.getMimeType());
                        threads.setThreadSize(idx, fileInfo.getSize());
                        threads.setThreadContent(idx, fileInfo.getContent());

                        downloadThread(idx);

                    } else {
                        downloadThread(idx);
                    }

                    if (!isStopped()) {
                        if (!finished.get()) {
                            buildFailedNotification((int) idx, thread.getName());
                        }
                    }

                } catch (Throwable e) {
                    if (!isStopped()) {
                        buildFailedNotification((int) idx, thread.getName());
                    }
                    throw e;
                } finally {
                    if (threads.isThreadLeaching(idx)) {
                        threads.resetThreadLeaching(idx);
                    }
                    threads.resetThreadWork(idx);
                }


            } else {
                // normal case like content of files
                final long size = thread.getSize();
                AtomicLong refresh = new AtomicLong(System.currentTimeMillis());
                try (InputStream inputStream = getApplicationContext().getContentResolver()
                        .openInputStream(uri)) {
                    Objects.requireNonNull(inputStream);


                    CID cid = ipfs.storeInputStream(inputStream, new Progress() {
                        @Override
                        public boolean isClosed() {
                            return isStopped();
                        }


                        @Override
                        public boolean doProgress() {
                            long time = System.currentTimeMillis();
                            long diff = time - refresh.get();
                            boolean doProgress = (diff > InitApplication.REFRESH);
                            if (doProgress) {
                                refresh.set(time);
                            }
                            return !isStopped() && doProgress;
                        }

                        @Override
                        public void setProgress(int percent) {
                            threads.setThreadProgress(idx, percent);
                        }
                    }, size);
                    if (!isStopped()) {
                        Objects.requireNonNull(cid);

                        threads.setThreadDone(idx, cid);
                        docs.finishDocument(idx);

                    }
                } catch (Throwable e) {
                    if (!isStopped()) {
                        threads.setThreadError(idx);
                        buildFailedNotification((int) idx, thread.getName());
                    }
                    throw e;
                } finally {
                    if (threads.isThreadLeaching(idx)) {
                        threads.resetThreadLeaching(idx);
                    }
                    threads.resetThreadWork(idx);
                }
            }
        } catch (Throwable e) {
            LogUtils.error(TAG, e);
            return Result.failure();
        } finally {
            LogUtils.info(TAG, " finish onStart [" + (System.currentTimeMillis() - start) + "]...");
        }

        return Result.success();

    }

    private void downloadThread(long idx) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(4);

        List<Thread> works = new ArrayList<>();

        if (!isStopped()) {
            downloadLinks(works, idx);
        }

        if (!isStopped()) {
            defineDirSize();

            ConcurrentLinkedQueue<Future<Boolean>> futures = new ConcurrentLinkedQueue<>();
            for (Thread work : works) {
                futures.add(executor.submit(() -> download(work)));
            }

            do {

                for (Future<Boolean> future : futures) {
                    if (future.isDone() || future.isCancelled()) {
                        futures.remove(future);
                    }
                }

                java.lang.Thread.sleep(1000);

                if (isStopped()) {
                    executor.shutdown();
                    executor.shutdownNow();
                    break;
                }

            } while (!futures.isEmpty());
        }
    }

    private void defineDirSize() {
        Thread thread = threads.getThreadByIdx(idx);
        Objects.requireNonNull(thread);
        if (thread.isDir() && thread.getSize() == 0) {
            updateParentSize(idx);
        }
    }

    private void updateParentSize(long idx) {
        long size = threads.getChildrenSummarySize(ipfs.getLocation(), idx);
        threads.setThreadSize(idx, size);
    }


    private void checkParentSeeding(long parent) {

        if (parent == 0L) {
            return;
        }

        try {
            int allSeeding = 0;

            updateParentSize(parent);

            List<Thread> list = threads.getChildren(ipfs.getLocation(), parent);
            for (Thread entry : list) {
                if (entry.isSeeding()) {
                    allSeeding++;
                }
            }
            boolean seeding = allSeeding == list.size();

            if (seeding) {
                threads.setThreadDone(parent);
                checkParentSeeding(threads.getThreadParent(parent));
            }

        } catch (Throwable e) {
            LogUtils.error(TAG, e);
        }

    }

    private boolean download(@NonNull Thread thread) {

        long start = System.currentTimeMillis();

        LogUtils.info(TAG, " start [" + (System.currentTimeMillis() - start) + "]...");
        boolean success = false;
        try {

            long threadIdx = thread.getIdx();

            Objects.requireNonNull(thread);
            long size = thread.getSize();
            String filename = thread.getName();
            Objects.requireNonNull(filename);

            CID cid = thread.getContent();
            Objects.requireNonNull(cid);

            AtomicLong started = new AtomicLong(System.currentTimeMillis());

            long parent = thread.getParent();
            if (ipfs.isEmptyDir(cid)) {
                // empty directory
                threads.setThreadDone(threadIdx);
                threads.setThreadSize(threadIdx, 0L);
                threads.setThreadMimeType(threadIdx, MimeType.DIR_MIME_TYPE);
                success = true;
            } else {
                File file = FileProvider.getInstance(
                        getApplicationContext()).createDataFile(cid);

                AtomicLong refresh = new AtomicLong(System.currentTimeMillis());
                success = ipfs.loadToFile(file, cid,
                        new Progress() {
                            @Override
                            public boolean isClosed() {
                                return isStopped();
                            }


                            @Override
                            public void setProgress(int percent) {
                                threads.setThreadProgress(threadIdx, percent);
                                reportProgress(filename, percent);
                            }

                            @Override
                            public boolean doProgress() {
                                started.set(System.currentTimeMillis());
                                long time = System.currentTimeMillis();
                                long diff = time - refresh.get();
                                boolean doProgress = (diff > InitApplication.REFRESH);
                                if (doProgress) {
                                    refresh.set(time);
                                }
                                return !isStopped() && doProgress;
                            }


                        });

                if (success) {
                    threads.setThreadDone(threadIdx);

                    if (size != file.length()) {
                        threads.setThreadSize(threadIdx, file.length());
                    }

                    String mimeType = thread.getMimeType();
                    if (mimeType.isEmpty()) {

                        ContentInfo contentInfo = ContentInfoUtil.getInstance(
                                getApplicationContext()).getContentInfo(file);
                        if (contentInfo != null) {
                            String contentInfoMimeType = contentInfo.getMimeType();
                            if (contentInfoMimeType != null) {
                                mimeType = contentInfoMimeType;
                            } else {
                                mimeType = MimeType.OCTET_MIME_TYPE;
                            }
                        } else {
                            mimeType = MimeType.OCTET_MIME_TYPE;
                        }
                        threads.setThreadMimeType(threadIdx, mimeType);
                    }

                    Uri uri = FileProvider.getDataUri(getApplicationContext(), cid);
                    if (uri != null) {
                        threads.setThreadUri(idx, uri.toString());
                    }
                } else {
                    finished.set(false);
                    threads.resetThreadLeaching(threadIdx);
                }
            }

            synchronized (lock) {
                checkParentSeeding(parent);
            }


        } catch (Throwable e) {
            LogUtils.error(TAG, e);
        } finally {
            LogUtils.info(TAG, " finish onStart [" + (System.currentTimeMillis() - start) + "]...");
        }
        return success;
    }


    private void buildFailedNotification(int idx, @NonNull String name) {

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                getApplicationContext(), InitApplication.CHANNEL_ID);

        builder.setContentTitle(getApplicationContext().getString(R.string.download_failed, name));
        builder.setSmallIcon(R.drawable.download);
        Intent defaultIntent = new Intent(getApplicationContext(), MainActivity.class);
        int requestID = (int) System.currentTimeMillis();
        PendingIntent defaultPendingIntent = PendingIntent.getActivity(
                getApplicationContext(), requestID, defaultIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        builder.setContentIntent(defaultPendingIntent);
        Notification notification = builder.build();

        NotificationManager notificationManager = (NotificationManager)
                getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(idx, notification);
        }
    }


    private void reportProgress(@NonNull String info, int percent) {

        if (!isStopped()) {
            Notification notification;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification = createNotification(info, percent);
            } else {
                notification = createCompatNotification(info, percent);
            }

            if (mNotificationManager != null) {
                mNotificationManager.notify((int) idx, notification);
            }

        }
    }


    @RequiresApi(api = Build.VERSION_CODES.O)
    private Notification createNotification(@NonNull String title, int progress) {

        Notification.Builder builder;
        if (mLastNotification.get() != null) {
            builder = Notification.Builder.recoverBuilder(
                    getApplicationContext(), mLastNotification.get());
            builder.setProgress(100, progress, false);
            builder.setContentTitle(title);
            builder.setSubText("" + progress + "%");
            return builder.build();
        } else {
            builder = new Notification.Builder(getApplicationContext(), InitApplication.CHANNEL_ID);
        }

        PendingIntent intent = WorkManager.getInstance(getApplicationContext())
                .createCancelPendingIntent(getId());
        String cancel = getApplicationContext().getString(android.R.string.cancel);

        Intent main = new Intent(getApplicationContext(), MainActivity.class);

        int requestID = (int) System.currentTimeMillis();
        PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), requestID,
                main, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(getApplicationContext(), R.drawable.pause), cancel,
                intent).build();

        builder.setContentTitle(title)
                .setSubText("" + progress + "%")
                .setContentIntent(pendingIntent)
                .setProgress(100, progress, false)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.download)
                .addAction(action)
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.colorAccent))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setUsesChronometer(true)
                .setOngoing(true);

        return builder.build();
    }

    private Notification createCompatNotification(@NonNull String title, int progress) {

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                getApplicationContext(), InitApplication.CHANNEL_ID);


        PendingIntent intent = WorkManager.getInstance(getApplicationContext())
                .createCancelPendingIntent(getId());
        String cancel = getApplicationContext().getString(android.R.string.cancel);

        Intent main = new Intent(getApplicationContext(), MainActivity.class);

        int requestID = (int) System.currentTimeMillis();
        PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), requestID,
                main, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Action action = new NotificationCompat.Action.Builder(
                R.drawable.pause, cancel, intent).build();

        builder.setContentTitle(title)
                .setSubText("" + progress + "%")
                .setContentIntent(pendingIntent)
                .setProgress(100, progress, false)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.download)
                .addAction(action)
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.colorAccent))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setUsesChronometer(true)
                .setOngoing(true);

        return builder.build();
    }

    @Nullable
    private List<LinkInfo> getLinks(@NonNull CID cid) {
        return ipfs.getLinks(cid, this::isStopped);
    }


    private Thread getFolderThread(long parent, @NonNull CID cid) {

        List<Thread> entries =
                threads.getThreadsByContentAndParent(ipfs.getLocation(), cid, parent);
        if (!entries.isEmpty()) {
            return entries.get(0);
        }
        return null;
    }


    private List<Thread> evalLinks(long parent, @NonNull List<LinkInfo> links) {
        List<Thread> threadList = new ArrayList<>();

        for (LinkInfo link : links) {

            CID cid = link.getCid();
            Thread entry = getFolderThread(parent, cid);
            if (entry != null) {
                if (!entry.isSeeding()) {
                    threadList.add(entry);
                }
            } else {

                long idx = createThread(cid, link, parent);
                entry = threads.getThreadByIdx(idx);
                Objects.requireNonNull(entry);

                threadList.add(entry);
            }
        }

        return threadList;
    }

    private long createThread(@NonNull CID cid, @NonNull LinkInfo link, long parent) {

        String name = link.getName();
        String mimeType = null;
        if (link.isDirectory()) {
            mimeType = MimeType.DIR_MIME_TYPE;
        }
        long size = link.getSize();

        return docs.createDocument(parent, mimeType, cid, null,
                name, size, false, true);
    }


    private void downloadLinks(@NonNull List<Thread> works, long idx) {

        Thread thread = threads.getThreadByIdx(idx);
        Objects.requireNonNull(thread);

        CID cid = thread.getContent();
        Objects.requireNonNull(cid);

        List<LinkInfo> links = getLinks(cid);

        if (links != null) {
            if (links.isEmpty()) {
                if (!isStopped()) {
                    works.add(thread);
                }
            } else {

                // thread is directory
                if (!thread.isDir()) {
                    threads.setMimeType(thread, DocumentsContract.Document.MIME_TYPE_DIR);
                }

                List<Thread> children = evalLinks(thread.getIdx(), links);

                for (Thread child : children) {
                    if (!isStopped()) {
                        if (child.isDir()) {
                            downloadLinks(works, child.getIdx());
                        } else {
                            works.add(child);
                        }
                    }
                }
            }
        }
    }
}