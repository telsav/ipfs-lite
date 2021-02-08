package threads.server.core.books;

import androidx.room.RoomDatabase;

@androidx.room.Database(entities = {Bookmark.class}, version = 1, exportSchema = false)
public abstract class BookmarkDatabase extends RoomDatabase {


    public abstract BookmarkDao bookmarkDao();

}
