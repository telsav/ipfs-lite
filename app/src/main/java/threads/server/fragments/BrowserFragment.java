package threads.server.fragments;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.HttpAuthHandler;
import android.webkit.URLUtil;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import threads.LogUtils;
import threads.server.BuildConfig;
import threads.server.InitApplication;
import threads.server.MainActivity;
import threads.server.R;
import threads.server.core.DOCS;
import threads.server.core.events.EVENTS;
import threads.server.core.events.EventViewModel;
import threads.server.core.page.Bookmark;
import threads.server.core.page.PAGES;
import threads.server.core.peers.Content;
import threads.server.core.threads.THREADS;
import threads.server.services.LiteService;
import threads.server.utils.CustomWebChromeClient;
import threads.server.utils.MimeType;
import threads.server.utils.SelectionViewModel;
import threads.server.work.ClearCacheWorker;
import threads.server.work.UploadThreadWorker;

public class BrowserFragment extends Fragment implements
        SwipeRefreshLayout.OnRefreshListener {


    private static final String TAG = BrowserFragment.class.getSimpleName();

    private static final long CLICK_OFFSET = 500;
    private Context mContext;
    private WebView mWebView;
    private FragmentActivity mActivity;
    private BrowserFragment.ActionListener mListener;
    private ProgressBar mProgressBar;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private long mLastClickTime = 0;
    private MenuItem mActionBookmark;
    private CustomWebChromeClient mCustomWebChromeClient;

    @Override
    public void onDetach() {
        super.onDetach();
        mContext = null;
        mActivity = null;

    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mContext = context;
        mActivity = getActivity();
        mListener = (BrowserFragment.ActionListener) getActivity();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        super.onCreateOptionsMenu(menu, menuInflater);
        menuInflater.inflate(R.menu.menu_browser_fragment, menu);

        mActionBookmark = menu.findItem(R.id.action_bookmark);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.browser_view, container, false);
    }

    public boolean onBackPressed() {

        if (mWebView.canGoBack()) {
            mWebView.goBack();
            return true;
        }

        return false;
    }


    private void loadUrl(@NonNull String uri) {
        DOCS docs = DOCS.getInstance(mContext);


        mSwipeRefreshLayout.setDistanceToTriggerSync(999999);
        preload(Uri.parse(uri));
        checkBookmark(uri);
        mListener.updateTitle(uri);
        mWebView.loadUrl(uri);


    }

    private void preload(@NonNull Uri uri) {
        if (Objects.equals(uri.getScheme(), Content.IPFS) ||
                Objects.equals(uri.getScheme(), Content.IPNS)) {
            try {
                if (Objects.equals(uri.getScheme(), Content.IPNS)) {
                    PAGES pages = PAGES.getInstance(mContext);
                    String name = uri.getHost();
                    if (name != null) {
                        pages.removeResolver(name);
                    }
                }

                EVENTS.getInstance(mContext).progress(5);

            } catch (Throwable e) {
                LogUtils.error(TAG, e);
            }
        }
    }

    public boolean onOptionsItemSelected(@NonNull MenuItem item) {

        int itemId = item.getItemId();
        if (itemId == R.id.action_share) {

            try {
                if (SystemClock.elapsedRealtime() - mLastClickTime < CLICK_OFFSET) {
                    return true;
                }
                mLastClickTime = SystemClock.elapsedRealtime();

                String uri = mWebView.getUrl();


                ComponentName[] names = {new ComponentName(mContext, MainActivity.class)};

                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_link));
                intent.putExtra(Intent.EXTRA_TEXT, uri);
                intent.setType(MimeType.PLAIN_MIME_TYPE);
                intent.putExtra(DocumentsContract.EXTRA_EXCLUDE_SELF, true);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);


                Intent chooser = Intent.createChooser(intent, getText(R.string.share));
                chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, names);
                chooser.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(chooser);

            } catch (Throwable ignore) {
                EVENTS.getInstance(mContext).warning(
                        getString(R.string.no_activity_found_to_handle_uri));
            }

            return true;
        } else if (itemId == R.id.action_bookmark) {

            if (SystemClock.elapsedRealtime() - mLastClickTime < CLICK_OFFSET) {
                return true;
            }
            mLastClickTime = SystemClock.elapsedRealtime();

            try {
                String uri = mWebView.getUrl();

                Objects.requireNonNull(uri);

                PAGES pages = PAGES.getInstance(mContext);

                Bookmark bookmark = pages.getBookmark(uri);
                if (bookmark != null) {
                    String name = bookmark.getTitle();
                    pages.removeBookmark(bookmark);
                    if (mActionBookmark != null) {
                        mActionBookmark.setIcon(R.drawable.star_outline);
                    }
                    EVENTS.getInstance(mContext).warning(
                            getString(R.string.bookmark_removed, name));
                } else {
                    Bitmap bitmap = mCustomWebChromeClient.getFavicon(uri);

                    String title = mCustomWebChromeClient.getTitle(uri);
                    if (title == null) {
                        title = "" + mWebView.getTitle();
                    }

                    bookmark = pages.createBookmark(uri, title);
                    if (bitmap != null) {
                        bookmark.setBitmapIcon(bitmap);
                    }

                    pages.storeBookmark(bookmark);

                    if (mActionBookmark != null) {
                        mActionBookmark.setIcon(R.drawable.star);
                    }
                    EVENTS.getInstance(mContext).warning(
                            getString(R.string.bookmark_added, title));
                }


            } catch (Throwable throwable) {
                LogUtils.error(TAG, throwable);
            }
            return true;
        } else if (itemId == R.id.action_clear_cache) {

            try {

                mWebView.clearHistory();
                mWebView.clearCache(true);

                ClearCacheWorker.clearCache(mContext);

                EVENTS.getInstance(mContext).warning(
                        getString(R.string.clear_cache));

            } catch (Throwable e) {
                LogUtils.error(TAG, e);
            }
            return true;
        } else if (itemId == R.id.action_bookmarks) {


            if (SystemClock.elapsedRealtime() - mLastClickTime < CLICK_OFFSET) {
                return true;
            }
            mLastClickTime = SystemClock.elapsedRealtime();

            BookmarksDialogFragment dialogFragment = new BookmarksDialogFragment();

            dialogFragment.show(getChildFragmentManager(), BookmarksDialogFragment.TAG);

            return true;
        } else if (itemId == R.id.action_find_page) {


            if (SystemClock.elapsedRealtime() - mLastClickTime < CLICK_OFFSET) {
                return true;
            }
            mLastClickTime = SystemClock.elapsedRealtime();

            try {
                ((AppCompatActivity)
                        mActivity).startSupportActionMode(
                        createFindActionModeCallback());
            } catch (Throwable throwable) {
                LogUtils.error(TAG, throwable);
            }
            return true;
        } else if (itemId == R.id.action_history) {


            if (SystemClock.elapsedRealtime() - mLastClickTime < CLICK_OFFSET) {
                return true;
            }
            mLastClickTime = SystemClock.elapsedRealtime();

            try {
                HistoryDialogFragment dialogFragment = new HistoryDialogFragment();
                dialogFragment.show(getChildFragmentManager(), HistoryDialogFragment.TAG);
            } catch (Throwable throwable) {
                LogUtils.error(TAG, throwable);
            }
            return true;

        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final DOCS docs = DOCS.getInstance(mContext);

        mSwipeRefreshLayout = view.findViewById(R.id.swipe_container);
        mSwipeRefreshLayout.setOnRefreshListener(this);
        mSwipeRefreshLayout.setColorSchemeResources(R.color.colorAccent,
                android.R.color.holo_green_dark,
                android.R.color.holo_orange_dark,
                android.R.color.holo_blue_dark);

        mProgressBar = view.findViewById(R.id.progress_bar);
        mProgressBar.setVisibility(View.GONE);


        mWebView = view.findViewById(R.id.web_view);


        mCustomWebChromeClient = new CustomWebChromeClient(mActivity) {

            public void onProgressChanged(WebView view, int newProgress) {

                EVENTS.getInstance(mContext).progress(newProgress);

            }
        };
        mWebView.setWebChromeClient(mCustomWebChromeClient);

        InitApplication.setWebSettings(mWebView, getUserAgent());

        EventViewModel eventViewModel =
                new ViewModelProvider(this).get(EventViewModel.class);


        eventViewModel.getProgress().observe(getViewLifecycleOwner(), (event) -> {
            try {
                if (event != null) {

                    String content = event.getContent();
                    if (!content.isEmpty()) {
                        int value = Integer.parseInt(content);
                        if (value <= 0 || value >= 100) {
                            mProgressBar.setVisibility(View.GONE);
                        } else {
                            mProgressBar.setVisibility(View.VISIBLE);
                            mProgressBar.setProgress(value);
                        }
                    }


                    eventViewModel.removeEvent(event);

                }
            } catch (Throwable e) {
                LogUtils.error(TAG, e);
            }

        });

        SelectionViewModel mSelectionViewModel = new ViewModelProvider(mActivity).get(SelectionViewModel.class);


        mSelectionViewModel.getUri().observe(getViewLifecycleOwner(), (uri) -> {
            if (uri != null) {
                loadUrl(uri);
            }
        });


        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {

            try {
                LogUtils.error(TAG, "downloadUrl : " + url);
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                Uri uri = Uri.parse(url);
                if (Objects.equals(uri.getScheme(), Content.IPFS) ||
                        Objects.equals(uri.getScheme(), Content.IPNS)) {
                    downloader(uri, filename, mimeType, contentLength);
                } else {
                    downloader(uri, filename, mimeType, contentLength);
                }


            } catch (Throwable throwable) {
                LogUtils.error(TAG, throwable);
            }
        });

        mWebView.setWebViewClient(new WebViewClient() {


            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                LogUtils.error(TAG, "onPageCommitVisible " + url);

                EVENTS.getInstance(mContext).progress(0);

            }


            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {

                try {
                    EVENTS.getInstance(mContext).warning(
                            "Login mechanism not yet implemented");
                } catch (Throwable throwable) {
                    LogUtils.error(TAG, throwable);
                }
                super.onReceivedHttpAuthRequest(view, handler, host, realm);
            }


            @Override
            public void onLoadResource(WebView view, String url) {
                LogUtils.error(TAG, "onLoadResource : " + url);
                super.onLoadResource(view, url);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                LogUtils.error(TAG, "doUpdateVisitedHistory : " + url + " " + isReload);

                mSwipeRefreshLayout.setDistanceToTriggerSync(100);
                checkBookmark(url);
                mListener.updateTitle(url);
                super.doUpdateVisitedHistory(view, url, isReload);
            }

            @Override
            public void onPageStarted(WebView view, String uri, Bitmap favicon) {
                LogUtils.error(TAG, "onPageStarted : " + uri);

                mSwipeRefreshLayout.setDistanceToTriggerSync(999999);
                checkBookmark(uri);
                mListener.updateTitle(uri);
            }


            @Override
            public void onPageFinished(WebView view, String url) {

                LogUtils.error(TAG, "onPageStarted : " + url);

            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                LogUtils.error(TAG, "" + error.getDescription());
            }


            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {

                try {
                    Uri uri = request.getUrl();
                    LogUtils.error(TAG, "shouldOverrideUrlLoading : " + uri);

                    if (Objects.equals(uri.getScheme(), Content.HTTP) ||
                            Objects.equals(uri.getScheme(), Content.HTTPS)) {
                        return false;
                    } else if (Objects.equals(uri.getScheme(), Content.IPNS) ||
                            Objects.equals(uri.getScheme(), Content.IPFS)) {

                        String res = uri.getQueryParameter("download");
                        if (Objects.equals(res, "1")) {
                            final AtomicLong time = new AtomicLong(System.currentTimeMillis());

                            long timeout = InitApplication.getConnectionTimeout(mContext) * 1000;
                            DOCS.FileInfo fileInfo = docs.getFileInfo(uri, () -> {
                                boolean abort = Thread.currentThread().isInterrupted();
                                boolean hasTimeout = (System.currentTimeMillis() - time.get() > timeout);
                                return abort || hasTimeout;
                            });
                            Objects.requireNonNull(fileInfo);
                            downloader(uri, fileInfo.getFilename(), fileInfo.getMimeType(),
                                    fileInfo.getSize());
                            return true;
                        }

                        return false;

                    } else if (Objects.equals(uri.getScheme(), Content.MAGNET)) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);

                            startActivity(intent);

                        } catch (Throwable ignore) {
                            EVENTS.getInstance(mContext).warning(
                                    getString(R.string.no_activity_found_to_handle_uri));
                        }
                        return true;
                    } else {
                        try {
                            // all other stuff
                            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);

                            startActivity(intent);

                        } catch (Throwable ignore) {
                            EVENTS.getInstance(mContext).warning(
                                    getString(R.string.no_activity_found_to_handle_uri));
                        }
                        return true;
                    }

                } catch (Throwable throwable) {
                    LogUtils.error(TAG, throwable);
                }
                return false;

            }


            public WebResourceResponse createEmptyResource() {
                return new WebResourceResponse("text/plain", Content.UTF8,
                        new ByteArrayInputStream("".getBytes()));
            }

            public WebResourceResponse createErrorMessage(@NonNull Throwable throwable) {
                String message = docs.generateErrorHtml(throwable);
                return new WebResourceResponse("text/html", Content.UTF8,
                        new ByteArrayInputStream(message.getBytes()));
            }


            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {

                try {
                    Uri uri = request.getUrl();
                    LogUtils.error(TAG, "shouldInterceptRequest : " + uri.toString());

                    if (Objects.equals(uri.getScheme(), Content.IPNS) ||
                            Objects.equals(uri.getScheme(), Content.IPFS)) {

                        String host = getHost(uri.toString());
                        if (host != null) {
                            LiteService.connect(mContext, host);
                        }

                        {
                            Uri newUri = docs.invalidUri(uri);
                            if (!Objects.equals(uri, newUri)) {
                                return createEmptyResource();
                            }
                        }


                        final AtomicLong time = new AtomicLong(System.currentTimeMillis());
                        long timeout = InitApplication.getConnectionTimeout(mContext) * 1000;
                        return docs.getResponse(uri, () ->
                                (System.currentTimeMillis() - time.get() > timeout));
                    }
                } catch (Throwable throwable) {
                    return createErrorMessage(throwable);
                }
                return null;
            }
        });

    }


    private String getUserAgent() {
        return getString(R.string.app_name) + "/" +
                BuildConfig.VERSION_NAME + " (Linux; Android "
                + Build.VERSION.RELEASE + "; wv)";
    }


    private void checkBookmark(@Nullable String uri) {
        try {
            if (uri != null) {
                PAGES pages = PAGES.getInstance(mContext);
                if (pages.hasBookmark(uri)) {
                    if (mActionBookmark != null) {
                        mActionBookmark.setIcon(R.drawable.star);
                    }
                } else {
                    if (mActionBookmark != null) {
                        mActionBookmark.setIcon(R.drawable.star_outline);
                    }
                }
            } else {
                if (mActionBookmark != null) {
                    mActionBookmark.setVisible(false);
                }
            }
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
    }


    private void downloader(@NonNull Uri uri, @NonNull String filename, @NonNull String mimeType,
                            long size) {
        final DOCS docs = DOCS.getInstance(mContext);
        final THREADS threads = THREADS.getInstance(mContext);
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setTitle(R.string.download_title);
        builder.setMessage(filename);

        builder.setPositiveButton(getString(android.R.string.yes), (dialog, which) -> {

            long idx = docs.createDocument(0L, mimeType, null, uri,
                    filename, size, false, true);

            UUID work = UploadThreadWorker.load(mContext, idx, false);
            threads.setThreadWork(idx, work);

            EVENTS.getInstance(mContext).warning(filename);

        });
        builder.setNeutralButton(getString(android.R.string.cancel),
                (dialog, which) -> dialog.cancel());
        builder.show();
        EVENTS.getInstance(mContext).progress(0);
    }


    @Override
    public void onRefresh() {
        mSwipeRefreshLayout.setRefreshing(true);

        try {
            preload(Uri.parse(mWebView.getUrl()));
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
        try {
            mWebView.reload();
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        } finally {
            mSwipeRefreshLayout.setRefreshing(false);
        }

    }

    private ActionMode.Callback createFindActionModeCallback() {
        return new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                mode.getMenuInflater().inflate(R.menu.menu_find_action_mode, menu);


                MenuItem action_mode_find = menu.findItem(R.id.action_mode_find);
                EditText mFindText = (EditText) action_mode_find.getActionView();
                mFindText.setMinWidth(200);
                mFindText.setMaxWidth(400);
                mFindText.setSingleLine();
                mFindText.setBackgroundResource(android.R.color.transparent);
                mFindText.setHint(R.string.find_page);
                mFindText.setFocusable(true);
                mFindText.requestFocus();

                mFindText.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

                    }

                    @Override
                    public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

                    }

                    @Override
                    public void afterTextChanged(Editable editable) {
                        mWebView.findAllAsync(mFindText.getText().toString());
                    }
                });


                mode.setTitle("0/0");

                mWebView.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) -> {
                    try {
                        String result = "" + activeMatchOrdinal + "/" + numberOfMatches;
                        mode.setTitle(result);
                    } catch (Throwable throwable) {
                        LogUtils.error(TAG, throwable);
                    }
                });

                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == R.id.action_mode_previous) {


                    if (SystemClock.elapsedRealtime() - mLastClickTime < CLICK_OFFSET) {
                        return true;
                    }
                    mLastClickTime = SystemClock.elapsedRealtime();

                    try {
                        mWebView.findNext(false);
                    } catch (Throwable throwable) {
                        LogUtils.error(TAG, throwable);
                    }

                    return true;
                } else if (itemId == R.id.action_mode_next) {


                    if (SystemClock.elapsedRealtime() - mLastClickTime < CLICK_OFFSET) {
                        return true;
                    }
                    mLastClickTime = SystemClock.elapsedRealtime();
                    try {
                        mWebView.findNext(true);
                    } catch (Throwable throwable) {
                        LogUtils.error(TAG, throwable);
                    }

                    return true;

                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                try {
                    mWebView.clearMatches();
                    mWebView.setFindListener(null);
                } catch (Throwable throwable) {
                    LogUtils.error(TAG, throwable);
                }
            }
        };

    }


    @NonNull
    public WebView getWebView() {
        return mWebView;
    }


    @Nullable
    private String getHost(@NonNull String url) {
        try {
            Uri uri = Uri.parse(url);
            if (Objects.equals(uri.getScheme(), Content.IPNS)) {
                return uri.getHost();
            }
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
        return null;
    }

    public interface ActionListener {

        void updateTitle(@Nullable String uri);

    }
}