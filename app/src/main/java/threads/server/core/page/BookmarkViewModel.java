package threads.server.core.page;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import java.util.List;

public class BookmarkViewModel extends AndroidViewModel {
    private final PageDatabase pageDatabase;

    public BookmarkViewModel(@NonNull Application application) {
        super(application);
        pageDatabase = PAGES.getInstance(
                application.getApplicationContext()).getPageDatabase();
    }

    public LiveData<List<Bookmark>> getBookmarks() {
        return pageDatabase.bookmarkDao().getLiveDataBookmarks();
    }
}