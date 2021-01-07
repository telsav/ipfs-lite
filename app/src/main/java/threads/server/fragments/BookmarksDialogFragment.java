package threads.server.fragments;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Comparator;
import java.util.Objects;

import threads.LogUtils;
import threads.server.R;
import threads.server.core.page.Bookmark;
import threads.server.core.page.BookmarkViewModel;
import threads.server.utils.BookmarksViewAdapter;
import threads.server.utils.SelectionViewModel;
import threads.server.utils.SwipeToDeleteCallback;

public class BookmarksDialogFragment extends DialogFragment implements BookmarksViewAdapter.BookmarkListener {
    public static final String TAG = BookmarksDialogFragment.class.getSimpleName();

    private static final int CLICK_OFFSET = 500;
    private long mLastClickTime = 0;
    private BookmarksViewAdapter mBookmarksViewAdapter;
    private Context mContext;
    private FragmentActivity mActivity;
    private SelectionViewModel mSelectionViewModel;

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
    }


    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

        Dialog dialog = new Dialog(mContext, R.style.ThreadsThemeFullscreen);
        dialog.setContentView(R.layout.booksmark_view);

        Toolbar mToolbar = dialog.findViewById(R.id.toolbar);
        Objects.requireNonNull(mToolbar);
        mToolbar.setTitle(R.string.bookmarks);
        mToolbar.setNavigationIcon(R.drawable.arrow_left);
        mToolbar.setNavigationOnClickListener(v -> {
            if (SystemClock.elapsedRealtime() - mLastClickTime < CLICK_OFFSET) {
                return;
            }
            mLastClickTime = SystemClock.elapsedRealtime();

            dismiss();
        });


        mSelectionViewModel = new ViewModelProvider(mActivity).get(SelectionViewModel.class);


        RecyclerView bookmarks = dialog.findViewById(R.id.bookmarks);
        Objects.requireNonNull(bookmarks);


        bookmarks.setLayoutManager(new LinearLayoutManager(mContext));
        mBookmarksViewAdapter = new BookmarksViewAdapter(mContext, this);
        bookmarks.setAdapter(mBookmarksViewAdapter);
        ItemTouchHelper itemTouchHelper = new
                ItemTouchHelper(new SwipeToDeleteCallback(mBookmarksViewAdapter));
        itemTouchHelper.attachToRecyclerView(bookmarks);


        BookmarkViewModel bookmarkViewModel =
                new ViewModelProvider(this).get(BookmarkViewModel.class);

        bookmarkViewModel.getBookmarks().observe(this, (marks -> {
            try {
                if (marks != null) {
                    marks.sort(Comparator.comparing(Bookmark::getTimestamp).reversed());
                    mBookmarksViewAdapter.setBookmarks(marks);
                }
            } catch (Throwable throwable) {
                LogUtils.error(TAG, throwable);
            }
        }));

        Window window = dialog.getWindow();
        if (window != null) {
            window.getAttributes().windowAnimations = R.style.DialogAnimationFullscreen;
        }

        return dialog;
    }

    @Override
    public void onClick(@NonNull Bookmark bookmark) {
        if (SystemClock.elapsedRealtime() - mLastClickTime < CLICK_OFFSET) {
            return;
        }
        mLastClickTime = SystemClock.elapsedRealtime();

        try {

            mSelectionViewModel.setUri(bookmark.getUri());

            dismiss();
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
    }
}