package threads.server.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.WorkManager;

import org.apache.commons.io.FilenameUtils;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import crypto.pb.Crypto;
import threads.lite.IPFS;
import threads.lite.LogUtils;
import threads.lite.cid.Cid;
import threads.lite.cid.Multiaddr;
import threads.lite.cid.PeerId;
import threads.lite.core.Closeable;
import threads.lite.core.ClosedException;
import threads.lite.core.TimeoutCloseable;
import threads.lite.format.Node;
import threads.lite.host.DnsResolver;
import threads.lite.ipns.Ipns;
import threads.lite.utils.Link;
import threads.server.InitApplication;
import threads.server.Settings;
import threads.server.core.books.BOOKS;
import threads.server.core.books.Bookmark;
import threads.server.core.pages.PAGES;
import threads.server.core.pages.Page;
import threads.server.core.threads.THREADS;
import threads.server.core.threads.Thread;
import threads.server.magic.ContentInfo;
import threads.server.magic.ContentInfoUtil;
import threads.server.services.MimeTypeService;
import threads.server.utils.MimeType;

public class DOCS {

    private static final String TAG = DOCS.class.getSimpleName();
    private static final String INDEX_HTML = "index.html";
    private static final HashSet<Long> runs = new HashSet<>();
    private static final HashSet<Uri> uris = new HashSet<>();
    private static DOCS INSTANCE = null;
    private final IPFS ipfs;
    private final BOOKS books;
    private final THREADS threads;
    private final PAGES pages;
    private final String host;
    private final Hashtable<String, String> resolves = new Hashtable<>();

    private boolean isRedirectIndex;
    private boolean isRedirectUrl;

    private DOCS(@NonNull Context context) {
        ipfs = IPFS.getInstance(context);
        threads = THREADS.getInstance(context);
        pages = PAGES.getInstance(context);
        books = BOOKS.getInstance(context);
        host = ipfs.getPeerID().toBase32();
        refreshRedirectOptions(context);
        initPinsPage();
    }

    public static DOCS getInstance(@NonNull Context context) {

        if (INSTANCE == null) {
            synchronized (DOCS.class) {
                if (INSTANCE == null) {
                    INSTANCE = new DOCS(context);
                }
            }
        }
        return INSTANCE;
    }


    private void pageConnect(@NonNull PeerId peerId, @NonNull Closeable closeable) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String pid = peerId.toBase58();
                Page page = pages.getPage(pid);
                boolean connected = false;
                if (page != null) {
                    String address = page.getAddress();
                    if (!address.isEmpty()) {
                        ipfs.addMultiAddress(peerId, new Multiaddr(address));
                        connected = ipfs.connect(peerId, InitApplication.USER_GRACE_PERIOD,
                                new TimeoutCloseable(IPFS.CONNECT_TIMEOUT));
                    }
                }
                if (!connected) {
                    connected = ipfs.swarmConnect(peerId, InitApplication.USER_GRACE_PERIOD,
                            new TimeoutCloseable(10));
                }

                if (page != null && connected) {
                    Multiaddr info = ipfs.remoteAddress(peerId, closeable);
                    String address = info.toString();
                    if (!address.isEmpty() && !address.contains(Content.CIRCUIT)) {
                        if (!Objects.equals(address, page.getAddress())) {
                            pages.setPageAddress(pid, address);
                            pages.resetBootstrap(pid);
                        } else {
                            pages.incrementRating(pid);
                            // success here, same address
                            if (!page.isBootstrap()) {
                                pages.setBootstrap(pid);
                            }
                        }
                    } else {
                        if (!page.getAddress().isEmpty()) {
                            pages.setPageAddress(pid, "");
                        }
                        if (page.isBootstrap()) {
                            pages.resetBootstrap(pid);
                        }
                    }
                }
                LogUtils.debug(TAG, "Connect " + pid + " " + connected);

            } catch (ClosedException ignore) {
            } catch (Throwable throwable) {
                LogUtils.error(TAG, throwable.getMessage());
            } finally {
                LogUtils.info(TAG, " finish onStart ...");
            }
        });
    }


    public void updateBookmarkTitle(@NonNull Uri uri, @NonNull String title) {
        books.setBookmarkTitle(uri.toString(), title);
    }

    public void updateBookmarkIcon(@NonNull Uri uri, @NonNull Bitmap icon) {
        Bookmark bookmark = books.getBookmark(uri.toString());
        if (bookmark != null) {
            bookmark.setBitmapIcon(icon);
            books.storeBookmark(bookmark);
        }
    }

    public int numUris() {
        synchronized (TAG.intern()) {
            return uris.size();
        }
    }

    public void detachUri(@NonNull Uri uri) {
        synchronized (TAG.intern()) {
            uris.remove(uri);
        }
    }

    public void attachUri(@NonNull Uri uri) {
        synchronized (TAG.intern()) {
            uris.add(uri);
        }
    }

    public void attachThread(@NonNull Long thread) {
        synchronized (TAG.intern()) {
            runs.add(thread);
        }
    }


    public void releaseContent() {
        ipfs.reset();
    }

    public void releaseThreads() {
        synchronized (TAG.intern()) {
            runs.clear();
        }
    }

    public boolean shouldRun(@NonNull Long thread) {
        synchronized (TAG.intern()) {
            return runs.contains(thread);
        }
    }

    public String getHost() {
        return host;
    }

    public boolean foreignPage(@NonNull Uri uri) {
        return !Objects.equals(uri.getHost(), getHost());
    }

    @NonNull
    public Uri getPinsPageUri() {
        return Uri.parse(Content.IPNS + "://" + getHost());
    }

    @Nullable
    public String getLocalName() {
        return pages.getPageContent(ipfs.getPeerID().toBase58());
    }

    private void deleteDocument(long idx) {


        List<Thread> children = threads.getChildren(idx);
        for (Thread thread : children) {
            deleteDocument(thread.getIdx());
        }

        try {
            removeFromParentDocument(idx);
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }

        threads.setThreadsDeleting(idx);

        try {
            updateParentSize(idx);
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }

        deleteThread(idx);

    }

    public void deleteDocument(@Nullable Context context, long idx) {

        if (context != null) {

            UUID uuid = threads.getThreadWork(idx);
            if (uuid != null) {
                WorkManager.getInstance(context).cancelWorkById(uuid);
            }

        }

        deleteDocument(idx);

    }

    private void deleteThread(long idx) {
        long start = System.currentTimeMillis();

        try {
            Thread thread = threads.getThreadByIdx(idx);
            if (thread != null) {
                if (thread.isDeleting()) {
                    String cid = thread.getContent();
                    threads.removeThread(thread);
                    if (cid != null) {
                        if (!threads.isReferenced(cid)) {
                            ipfs.rm(Cid.decode(cid));
                        }
                    }
                }
            }
        } catch (Throwable e) {
            LogUtils.error(TAG, e);
        } finally {
            LogUtils.info(TAG, " finish onStart [" + (System.currentTimeMillis() - start) + "]...");
        }

    }

    public void moveDocument(long idx, long sourceIdx, long targetIdx) {

        Thread thread = threads.getThreadByIdx(idx);
        Objects.requireNonNull(thread);
        Thread source = threads.getThreadByIdx(sourceIdx);
        Objects.requireNonNull(source);
        Thread target = threads.getThreadByIdx(targetIdx);
        Objects.requireNonNull(target);

        if (!Objects.equals(thread.getParent(), sourceIdx)) {
            throw new RuntimeException("Parent ");
        }

        try {
            removeFromParentDocument(idx);
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }


        String name = getUniqueName(source.getName(), targetIdx);
        threads.setThreadName(idx, name);

        threads.setThreadParent(idx, targetIdx);

        updateParentDocument(idx, "");

        try {
            updateDirectorySize(sourceIdx);
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }


        try {
            updateDirectorySize(targetIdx);
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
    }


    public String getUniqueName(@NonNull String name, long parent) {
        return getName(name, parent, 0);
    }

    private String getName(@NonNull String name, long parent, int index) {
        String searchName = name;
        if (index > 0) {
            try {
                String base = FilenameUtils.getBaseName(name);
                String extension = FilenameUtils.getExtension(name);
                if (extension.isEmpty()) {
                    searchName = searchName.concat(" (" + index + ")");
                } else {
                    String end = " (" + index + ")";
                    if (base.endsWith(end)) {
                        String realBase = base.substring(0, base.length() - end.length());
                        searchName = realBase.concat(" (" + index + ")").concat(".").concat(extension);
                    } else {
                        searchName = base.concat(" (" + index + ")").concat(".").concat(extension);
                    }
                }
            } catch (Throwable throwable) {
                LogUtils.error(TAG, throwable);
                searchName = searchName.concat(" (" + index + ")"); // just backup
            }
        }
        List<Thread> names = threads.getThreadsByNameAndParent(
                searchName, parent);
        if (!names.isEmpty()) {
            return getName(name, parent, ++index);
        }
        return searchName;
    }


    public void finishDocument(long idx) {
        updateParentDocument(idx, "");
        updateParentSize(idx);
    }

    private void updateParentSize(long idx) {
        long parent = threads.getThreadParent(idx);
        updateDirectorySize(parent);
    }

    private void updateDirectorySize(long parent) {
        if (parent > 0) {
            long parentSize = threads.getChildrenSummarySize(parent);
            threads.setThreadSize(parent, parentSize);
            updateParentSize(parent);
        }
    }


    private void updateParentDocument(long idx, @NonNull String oldName) {
        long parent = threads.getThreadParent(idx);

        Cid cid = Cid.decode(Objects.requireNonNull(threads.getThreadContent(idx)));
        Objects.requireNonNull(cid);
        String name = threads.getThreadName(idx);
        if (parent > 0) {
            Cid dirCid = Cid.decode(Objects.requireNonNull(threads.getThreadContent(parent)));
            Objects.requireNonNull(dirCid);
            if (!oldName.isEmpty()) {
                Cid dir = ipfs.rmLinkFromDir(dirCid, oldName);
                if (dir != null) {
                    dirCid = dir;
                }
            }
            Cid newDir = ipfs.addLinkToDir(dirCid, name, cid);
            Objects.requireNonNull(newDir);
            threads.setThreadContent(parent, newDir.String());
            threads.setThreadLastModified(parent, System.currentTimeMillis());
            updateParentDocument(parent, "");
        } else {
            Cid dirCid = Cid.decode(Objects.requireNonNull(pages.getPageContent(ipfs.getPeerID().toBase58())));
            Objects.requireNonNull(dirCid);
            if (!oldName.isEmpty()) {
                Cid dir = ipfs.rmLinkFromDir(dirCid, oldName);
                if (dir != null) {
                    dirCid = dir;
                }
            }
            Objects.requireNonNull(dirCid);
            Cid newDir = ipfs.addLinkToDir(dirCid, name, cid);
            Objects.requireNonNull(newDir);
            pages.setPageContent(ipfs.getPeerID().toBase58(), newDir.String());
        }
    }


    private void removeFromParentDocument(long idx) {

        Thread child = threads.getThreadByIdx(idx);
        if (child != null) {
            String name = child.getName();
            long parent = child.getParent();
            if (parent > 0) {
                Cid dirCid = Cid.decode(Objects.requireNonNull(threads.getThreadContent(parent)));
                Objects.requireNonNull(dirCid);
                Cid newDir = ipfs.rmLinkFromDir(dirCid, name);
                if (newDir != null) {
                    threads.setThreadContent(parent, newDir.String());
                    threads.setThreadLastModified(parent, System.currentTimeMillis());
                    updateParentDocument(parent, "");
                }
            } else {
                Cid dirCid = Cid.decode(Objects.requireNonNull
                        (pages.getPageContent(ipfs.getPeerID().toBase58())));
                Objects.requireNonNull(dirCid);
                Cid newDir = ipfs.rmLinkFromDir(dirCid, name);
                if (newDir != null) {
                    pages.setPageContent(ipfs.getPeerID().toBase58(), newDir.String());
                }
            }
        }
    }

    public long copyDocument(long parent, long idx) {
        Thread source = threads.getThreadByIdx(idx);
        Objects.requireNonNull(source);

        if (parent > 0) {
            Thread target = threads.getThreadByIdx(parent);
            Objects.requireNonNull(target);
        }

        String name = getUniqueName(source.getName(), parent);

        Thread thread = threads.createThread(parent);
        thread.setInit(source.isInit());
        thread.setName(name);
        thread.setContent(source.getContent());
        thread.setLeaching(source.isLeaching());
        thread.setDeleting(source.isDeleting());
        thread.setMimeType(source.getMimeType());
        thread.setSize(source.getSize());
        thread.setUri(source.getUri());
        thread.setSeeding(source.isSeeding());
        thread.setPosition(0L);
        thread.setProgress(0);
        thread.setWork(null);


        long res = threads.storeThread(thread);

        finishDocument(res);
        return res;
    }

    private String checkMimeType(@Nullable String mimeType, @NonNull String name) {
        boolean evalDisplayName = false;
        if (mimeType == null) {
            evalDisplayName = true;
        } else {
            if (mimeType.isEmpty()) {
                evalDisplayName = true;
            } else {
                if (Objects.equals(mimeType, MimeType.OCTET_MIME_TYPE)) {
                    evalDisplayName = true;
                }
            }
        }
        if (evalDisplayName) {
            mimeType = MimeTypeService.getMimeType(name);
        }
        return mimeType;
    }

    public long createDocument(long parent, @Nullable String type, @Nullable String content,
                               @Nullable Uri uri, String displayName, long size,
                               boolean seeding, boolean init) {
        String mimeType = checkMimeType(type, displayName);
        Thread thread = threads.createThread(parent);
        if (Objects.equals(mimeType, MimeType.DIR_MIME_TYPE)) {
            thread.setMimeType(MimeType.DIR_MIME_TYPE);
        } else {
            thread.setMimeType(mimeType);
        }

        String name = getUniqueName(displayName, parent);
        thread.setContent(content);
        thread.setInit(init);
        thread.setName(name);
        thread.setSize(size);
        thread.setSeeding(seeding);
        if (uri != null) {
            thread.setUri(uri.toString());
        }
        return threads.storeThread(thread);
    }

    public void renameDocument(long idx, String displayName) {
        String oldName = threads.getThreadName(idx);
        if (!Objects.equals(oldName, displayName)) {
            threads.setThreadName(idx, displayName);
            updateParentDocument(idx, oldName);
        }

    }


    public void initPinsPage() {
        try {
            Page page = getPinsPage();
            if (page == null) {
                page = pages.createPage(ipfs.getPeerID().toBase58());
                Cid dir = ipfs.createEmptyDir();
                Objects.requireNonNull(dir);
                page.setContent(dir.String());
                pages.storePage(page);
            }
            // just for backup, in case something happen before
            page = getPinsPage();
            Objects.requireNonNull(page);
            Cid dir = ipfs.createEmptyDir();
            Objects.requireNonNull(dir);

            List<Thread> pins = threads.getPins();

            boolean isEmpty = pins.isEmpty();
            if (!isEmpty) {
                for (Thread pin : pins) {
                    String link = pin.getContent();
                    Objects.requireNonNull(link);
                    String name = pin.getName();
                    dir = ipfs.addLinkToDir(dir, name, Cid.decode(link));
                    Objects.requireNonNull(dir);
                }
            }
            page.setContent(dir.String());
            pages.storePage(page);


        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
    }


    @NonNull
    private String getMimeType(@NonNull Context context, @NonNull Cid cid,
                               @NonNull Closeable closeable) throws ClosedException {

        if (ipfs.isDir(cid, closeable)) {
            return MimeType.DIR_MIME_TYPE;
        }
        return getContentMimeType(context, cid, closeable);
    }

    @NonNull
    private String getContentMimeType(@NonNull Context context, @NonNull Cid cid,
                                      @NonNull Closeable closeable) throws ClosedException {


        String mimeType = MimeType.OCTET_MIME_TYPE;

        try (InputStream in = ipfs.getLoaderStream(cid, closeable)) {
            ContentInfo info = ContentInfoUtil.getInstance(context).findMatch(in);

            if (info != null) {
                mimeType = info.getMimeType();
            }

        } catch (Throwable e) {
            if (closeable.isClosed()) {
                throw new ClosedException();
            }
        }

        return mimeType;
    }

    @NonNull
    public Uri getPath(@NonNull Thread thread) {

        Uri.Builder builder = new Uri.Builder();
        builder.scheme(Content.IPNS)
                .authority(getHost());
        List<Thread> ancestors = threads.getAncestors(thread.getIdx());
        for (Thread ancestor : ancestors) {
            builder.appendPath(ancestor.getName());
        }
        return builder.build();
    }

    public String generateErrorHtml(@NonNull Throwable throwable) {

        return "<html>" +
                "<head>" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=2, user-scalable=yes\">" +
                "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">" +
                "<title>" + "Error" + "</title>" + "</head><body><div <div style=\"padding: 16px; word-break:break-word; background-color: #696969; color: white;\">" +
                throwable.getMessage() +
                "</div></body></html>";
    }


    public String generateDirectoryHtml(@NonNull Uri uri, @NonNull String root, @NonNull List<String> paths, @Nullable List<Link> links) {
        String title = root;

        if (!paths.isEmpty()) {
            title = paths.get(paths.size() - 1);
        }


        StringBuilder answer = new StringBuilder("<html>" +
                "<head>" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=2, user-scalable=yes\">" +
                "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">" +
                "<title>" + title + "</title>");

        answer.append("</head><body>");


        answer.append("<div style=\"padding: 16px; word-break:break-word; background-color: #696969; color: white;\">Index of ").append(uri.toString()).append("</div>");

        if (links != null) {
            if (!links.isEmpty()) {
                answer.append("<form><table  width=\"100%\" style=\"border-spacing: 4px;\">");
                for (Link link : links) {

                    Uri.Builder builder = new Uri.Builder();
                    builder.scheme(uri.getScheme())
                            .authority(uri.getAuthority());
                    for (String path : paths) {
                        builder.appendPath(path);
                    }
                    builder.appendPath(link.getName());
                    builder.appendQueryParameter("download", "0");
                    Uri linkUri = builder.build();


                    answer.append("<tr>");

                    answer.append("<td>");

                    if (!link.isDirectory()) {
                        answer.append(MimeTypeService.getSvgResource(link.getName()));
                    } else {
                        answer.append(MimeTypeService.SVG_FOLDER);
                    }

                    answer.append("</td>");

                    answer.append("<td width=\"100%\" style=\"word-break:break-word\">");
                    answer.append("<a href=\"");
                    answer.append(linkUri.toString());
                    answer.append("\">");
                    answer.append(link.getName());
                    answer.append("</a>");
                    answer.append("</td>");

                    answer.append("<td>");
                    answer.append(getFileSize(link.getSize()));
                    answer.append("</td>");

                    answer.append("<td align=\"center\">");
                    String text = "<button style=\"float:none!important;display:inline;\" name=\"download\" value=\"1\" formenctype=\"text/plain\" formmethod=\"get\" type=\"submit\" formaction=\"" +
                            linkUri + "\">" + MimeTypeService.getSvgDownload() + "</button>";
                    answer.append(text);
                    answer.append("</td>");
                    answer.append("</tr>");
                }
                answer.append("</table></form>");
            }

        }
        answer.append("</body></html>");


        return answer.toString();
    }

    private String getFileSize(long size) {

        String fileSize;

        if (size < 1000) {
            fileSize = String.valueOf(size);
            return fileSize.concat(" B");
        } else if (size < 1000 * 1000) {
            fileSize = String.valueOf((double) (size / 1000));
            return fileSize.concat(" KB");
        } else {
            fileSize = String.valueOf((double) (size / (1000 * 1000)));
            return fileSize.concat(" MB");
        }
    }

    @NonNull
    public String resolveName(@NonNull Uri uri, @NonNull String name,
                              @NonNull Closeable closeable) throws ResolveNameException {

        if (Objects.equals(getHost(), name)) {
            String local = getLocalName();
            if (local != null) {
                return local;
            }
        }
        String pid = ipfs.decodeName(name);
        String resolved = resolves.get(pid);
        if (resolved != null) {
            return resolved;
        }

        long sequence = 0L;
        String cid = null;
        Page page = pages.getPage(pid);
        if (page != null) {
            sequence = page.getSequence();
            cid = page.getContent();
        } else {
            page = pages.createPage(pid);
            pages.storePage(page);
        }


        Ipns.Entry entry = ipfs.resolveName(name, sequence, closeable);
        if (entry == null) {

            if (cid != null) {
                resolves.put(pid, cid);
                return cid;
            }

            throw new ResolveNameException(uri.toString());
        }

        // todo not sure it this makes sense at all
        if (entry.getKeyType().equals(Crypto.KeyType.Ed25519)) {
            pageConnect(entry.getPeerId(), closeable);
        }

        resolves.put(pid, entry.getHash());
        pages.setPageContent(pid, entry.getHash());
        pages.setPageSequence(pid, entry.getSequence());
        return entry.getHash();
    }

    @Nullable
    public Node resolvePath(@NonNull Uri uri, @NonNull Closeable closeable) throws
            InvalidNameException, ClosedException, ResolveNameException {
        List<String> paths = uri.getPathSegments();

        String root = getRoot(uri, closeable);
        Objects.requireNonNull(root);

        return ipfs.resolveNode(Cid.decode(root), paths, closeable);
    }

    @NonNull
    public WebResourceResponse getResponse(@NonNull Context context,
                                           @NonNull Uri uri, @NonNull String root,
                                           @NonNull List<String> paths,
                                           @NonNull Closeable closeable) throws Exception {

        if (paths.isEmpty()) {
            if (ipfs.isDir(Cid.decode(root), closeable)) {
                List<Link> links = ipfs.links(Cid.decode(root), closeable);
                String answer = generateDirectoryHtml(uri, root, paths, links);
                return new WebResourceResponse(MimeType.HTML_MIME_TYPE, Content.UTF8,
                        new ByteArrayInputStream(answer.getBytes()));
            } else {
                String mimeType = getContentMimeType(context, Cid.decode(root), closeable);
                return getContentResponse(Cid.decode(root), mimeType, closeable);
            }


        } else {
            Cid cid = ipfs.resolve(Cid.decode(root), paths, closeable);
            if (cid == null) {
                throw new ContentException(uri.toString());
            }
            if (ipfs.isDir(cid, closeable)) {
                List<Link> links = ipfs.links(cid, closeable);
                String answer = generateDirectoryHtml(uri, root, paths, links);
                return new WebResourceResponse(MimeType.HTML_MIME_TYPE, Content.UTF8,
                        new ByteArrayInputStream(answer.getBytes()));

            } else {
                String mimeType = getMimeType(context, uri, cid, closeable);
                return getContentResponse(cid, mimeType, closeable);
            }
        }
    }

    @NonNull
    private WebResourceResponse getContentResponse(@NonNull Cid cid,
                                                   @NonNull String mimeType,
                                                   @NonNull Closeable closeable) throws ClosedException {

        try (InputStream in = ipfs.getLoaderStream(cid, closeable)) {

            if (closeable.isClosed()) {
                throw new ClosedException();
            }

            Map<String, String> responseHeaders = new HashMap<>();
            return new WebResourceResponse(mimeType, Content.UTF8, 200,
                    "OK", responseHeaders, new BufferedInputStream(in));
        } catch (Throwable throwable) {
            if (closeable.isClosed()) {
                throw new ClosedException();
            }
            throw new RuntimeException(throwable);
        }


    }


    @NonNull
    public String getFileName(@NonNull Uri uri) {

        List<String> paths = uri.getPathSegments();
        if (!paths.isEmpty()) {
            return paths.get(paths.size() - 1);
        } else {
            return "" + uri.getHost();
        }

    }

    @NonNull
    public String getContent(@NonNull Uri uri, @NonNull Closeable closeable)
            throws InvalidNameException, ResolveNameException, ClosedException {

        String host = uri.getHost();
        Objects.requireNonNull(host);

        String root = getRoot(uri, closeable);
        Objects.requireNonNull(root);

        List<String> paths = uri.getPathSegments();
        if (paths.isEmpty()) {
            return root;
        }

        return Objects.requireNonNull(ipfs.resolve(Cid.decode(root), paths, closeable)).String();
    }

    @NonNull
    public String getMimeType(@NonNull Context context,
                              @NonNull Uri uri,
                              @NonNull Cid cid,
                              @NonNull Closeable closeable) throws ClosedException {

        List<String> paths = uri.getPathSegments();
        if (!paths.isEmpty()) {
            String name = paths.get(paths.size() - 1);
            String mimeType = MimeTypeService.getMimeType(name);
            if (!mimeType.equals(MimeType.OCTET_MIME_TYPE)) {
                return mimeType;
            } else {
                return getMimeType(context, cid, closeable);
            }
        } else {
            return getMimeType(context, cid, closeable);
        }

    }

    @NonNull
    public Uri redirectUri(@NonNull Uri uri, @NonNull Closeable closeable)
            throws ResolveNameException, InvalidNameException, ClosedException {


        if (Objects.equals(uri.getScheme(), Content.IPNS) ||
                Objects.equals(uri.getScheme(), Content.IPFS)) {
            List<String> paths = uri.getPathSegments();
            String root = getRoot(uri, closeable);
            Objects.requireNonNull(root);
            return redirect(uri, root, paths, closeable);
        }
        return uri;
    }

    @NonNull
    private Uri redirect(@NonNull Uri uri, @NonNull String root,
                         @NonNull List<String> paths, @NonNull Closeable closeable)
            throws ClosedException {


        // check first paths
        // if like this .../ipfs/Qa..../
        // THIS IS A BIG HACK AND SHOULD NOT BE SUPPORTED
        if (paths.size() >= 2) {
            String protocol = paths.get(0);
            if (Objects.equals(protocol, Content.IPFS) ||
                    Objects.equals(protocol, Content.IPNS)) {
                String authority = paths.get(1);
                List<String> subPaths = new ArrayList<>(paths);
                subPaths.remove(protocol);
                subPaths.remove(authority);
                if (ipfs.isValidCID(authority)) {
                    if (Objects.equals(protocol, Content.IPFS)) {
                        Uri.Builder builder = new Uri.Builder();
                        builder.scheme(Content.IPFS)
                                .authority(authority);

                        for (String path : subPaths) {
                            builder.appendPath(path);
                        }
                        return builder.build();
                    } else if (Objects.equals(protocol, Content.IPNS)) {
                        Uri.Builder builder = new Uri.Builder();
                        builder.scheme(Content.IPNS)
                                .authority(authority);

                        for (String path : subPaths) {
                            builder.appendPath(path);
                        }
                        return builder.build();
                    }
                }
            }
        }

        if (isRedirectIndex) {
            Cid cid = ipfs.resolve(Cid.decode(root), paths, closeable);

            if (cid != null) {
                if (ipfs.isDir(cid, closeable)) {
                    boolean exists = ipfs.resolve(cid, INDEX_HTML, closeable);

                    if (exists) {
                        Uri.Builder builder = new Uri.Builder();
                        builder.scheme(uri.getScheme())
                                .authority(uri.getAuthority());
                        for (String path : paths) {
                            builder.appendPath(path);
                        }
                        builder.appendPath(INDEX_HTML);
                        return builder.build();
                    }
                }
            }
        }


        return uri;
    }


    @NonNull
    private String resolveDnsLink(@NonNull Uri uri, @NonNull String link,
                                  @NonNull Closeable closeable)
            throws ClosedException, InvalidNameException, ResolveNameException {

        List<String> paths = uri.getPathSegments();
        if (link.startsWith(IPFS.IPFS_PATH)) {
            return link.replaceFirst(IPFS.IPFS_PATH, "");
        } else if (link.startsWith(IPFS.IPNS_PATH)) {
            String cid = link.replaceFirst(IPFS.IPNS_PATH, "");
            if (!ipfs.decodeName(cid).isEmpty()) {
                Uri.Builder builder = new Uri.Builder();
                builder.scheme(Content.IPNS)
                        .authority(cid);
                for (String path : paths) {
                    builder.appendPath(path);
                }
                return resolveUri(builder.build(), closeable);
            } else {
                // is is assume like /ipns/<dns_link> = > therefore <dns_link> is url
                return resolveName(uri, cid, closeable);
            }
        } else {
            // is is assume that links is  <dns_link> is url

            Uri dnsUri = Uri.parse(link);
            if (dnsUri != null) {
                Uri.Builder builder = new Uri.Builder();
                builder.scheme(Content.IPNS)
                        .authority(dnsUri.getAuthority());
                for (String path : paths) {
                    builder.appendPath(path);
                }
                return resolveUri(builder.build(), closeable);
            }
        }
        throw new ResolveNameException(uri.toString());
    }

    @NonNull
    private String resolveUri(@NonNull Uri uri, @NonNull Closeable closeable)
            throws ResolveNameException, InvalidNameException, ClosedException {
        String host = uri.getHost();
        Objects.requireNonNull(host);

        if (!Objects.equals(uri.getScheme(), Content.IPNS)) {
            throw new RuntimeException();
        }

        if (ipfs.decodeName(host).isEmpty()) {

            String link = DnsResolver.resolveDnsLink(host);
            if (link.isEmpty()) {
                // could not resolved, maybe no NETWORK
                String dnsLink = null; // TODO books.getDnsLink(uri.toString());
                if (dnsLink == null) {
                    throw new DOCS.ResolveNameException(uri.toString());
                } else {
                    return resolveDnsLink(uri, dnsLink, closeable);
                }
            } else {
                // try to store value
                // TODO books.storeDnsLink(uri.toString(), link);
                return resolveDnsLink(uri, link, closeable);
            }

        } else {
            return resolveName(uri, host, closeable);
        }
    }

    @Nullable
    public String getRoot(@NonNull Uri uri, @NonNull Closeable closeable)
            throws ResolveNameException, InvalidNameException, ClosedException {
        String host = uri.getHost();
        Objects.requireNonNull(host);

        String root;
        if (Objects.equals(uri.getScheme(), Content.IPNS)) {
            root = resolveUri(uri, closeable);
        } else {
            if (!ipfs.isValidCID(host)) {
                throw new InvalidNameException(uri.toString());
            }
            root = host;
        }
        return root;
    }

    @Nullable
    public Page getPinsPage() {
        return pages.getPage(ipfs.getPeerID().toBase58());
    }

    public void bootstrap() {

        try {
            ipfs.bootstrap();

            List<Page> bootstraps = pages.getBootstraps(5);
            if (bootstraps.size() > 0) {
                ExecutorService executor = Executors.newFixedThreadPool(bootstraps.size());
                for (Page bootstrap : bootstraps) {
                    PeerId peerId = PeerId.fromBase58(bootstrap.getPid());
                    String address = bootstrap.getAddress();
                    if (!address.isEmpty()) {
                        ipfs.addMultiAddress(peerId, new Multiaddr(address));
                    }
                    executor.execute(() -> ipfs.connect(peerId, InitApplication.USER_GRACE_PERIOD,
                            new TimeoutCloseable(IPFS.CONNECT_TIMEOUT)));
                }
            }
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
    }

    @NonNull
    public WebResourceResponse getResponse(@NonNull Context context, @NonNull Uri uri,
                                           @NonNull Closeable closeable) throws Exception {

        List<String> paths = uri.getPathSegments();

        String root = getRoot(uri, closeable);
        Objects.requireNonNull(root);


        return getResponse(context, uri, root, paths, closeable);

    }


    @NonNull
    public Uri redirectHttp(@NonNull Uri uri) {
        try {
            if (Objects.equals(uri.getScheme(), Content.HTTP)) {
                String host = uri.getHost();
                Objects.requireNonNull(host);
                if (Objects.equals(host, "localhost") || Objects.equals(host, "127.0.0.1")) {
                    List<String> paths = uri.getPathSegments();
                    if (paths.size() >= 2) {
                        String protocol = paths.get(0);
                        String authority = paths.get(1);
                        List<String> subPaths = new ArrayList<>(paths);
                        subPaths.remove(protocol);
                        subPaths.remove(authority);
                        if (ipfs.isValidCID(authority)) {
                            if (Objects.equals(protocol, Content.IPFS)) {
                                Uri.Builder builder = new Uri.Builder();
                                builder.scheme(Content.IPFS)
                                        .authority(authority);

                                for (String path : subPaths) {
                                    builder.appendPath(path);
                                }
                                return builder.build();
                            } else if (Objects.equals(protocol, Content.IPNS)) {
                                Uri.Builder builder = new Uri.Builder();
                                builder.scheme(Content.IPNS)
                                        .authority(authority);

                                for (String path : subPaths) {
                                    builder.appendPath(path);
                                }
                                return builder.build();
                            }
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
        return uri;
    }


    @NonNull
    public Uri redirectHttps(@NonNull Uri uri) {
        try {
            if (isRedirectUrl && Objects.equals(uri.getScheme(), Content.HTTPS)) {


                List<String> paths = uri.getPathSegments();
                if (paths.size() >= 2) {
                    String protocol = paths.get(0);
                    if (Objects.equals(protocol, Content.IPFS) ||
                            Objects.equals(protocol, Content.IPNS)) {
                        String authority = paths.get(1);
                        List<String> subPaths = new ArrayList<>(paths);
                        subPaths.remove(protocol);
                        subPaths.remove(authority);
                        if (ipfs.isValidCID(authority)) {
                            if (Objects.equals(protocol, Content.IPFS)) {
                                Uri.Builder builder = new Uri.Builder();
                                builder.scheme(Content.IPFS)
                                        .authority(authority);

                                for (String path : subPaths) {
                                    builder.appendPath(path);
                                }
                                return builder.build();
                            } else if (Objects.equals(protocol, Content.IPNS)) {
                                Uri.Builder builder = new Uri.Builder();
                                builder.scheme(Content.IPNS)
                                        .authority(authority);

                                for (String path : subPaths) {
                                    builder.appendPath(path);
                                }
                                return builder.build();
                            }
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
        return uri;
    }


    @Nullable
    public String getHost(@NonNull Uri uri) {
        try {
            if (Objects.equals(uri.getScheme(), Content.IPNS)) {
                return uri.getHost();
            }
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
        return null;
    }

    public void cleanupResolver(@NonNull Uri uri) {

        try {
            String host = getHost(uri);
            if (host != null) {
                String pid = ipfs.decodeName(host);
                if (!pid.isEmpty()) {
                    resolves.remove(pid);
                }
            }

        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }

    }

    public boolean isPrivateNetwork(@NonNull Context context) {
        return false;
    }

    public void refreshRedirectOptions(@NonNull Context context) {
        isRedirectIndex = Settings.isRedirectIndexEnabled(context);
        isRedirectUrl = Settings.isRedirectUrlEnabled(context);
    }


    public static class ContentException extends Exception {

        public ContentException(@NonNull String name) {
            super("Content not found for " + name);
        }
    }

    public static class ResolveNameException extends Exception {


        public ResolveNameException(@NonNull String name) {
            super("Resolve name failed for " + name);
        }

    }

    public static class InvalidNameException extends Exception {


        public InvalidNameException(@NonNull String name) {
            super("Invalid name detected for " + name);
        }

    }
}
