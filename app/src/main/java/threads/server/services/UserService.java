package threads.server.services;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.gson.Gson;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import threads.lite.IPFS;
import threads.lite.LogUtils;
import threads.lite.cid.PeerId;
import threads.server.core.DeleteOperation;
import threads.server.core.events.EVENTS;
import threads.server.core.peers.PEERS;

public class UserService {


    private static final String TAG = UserService.class.getSimpleName();


    public static void deleteUsers(@NonNull Context context, String... pids) {


        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            long start = System.currentTimeMillis();
            try {
                PEERS.getInstance(context).removeUsers(pids);
                IPFS ipfs = IPFS.getInstance(context);
                for (String pid : pids) {
                    ipfs.swarmReduce(PeerId.fromBase58(pid));
                }
            } catch (Throwable e) {
                LogUtils.error(TAG, e);
            } finally {
                LogUtils.info(TAG, " deleteUsers finish onStart [" +
                        (System.currentTimeMillis() - start) + "]...");
            }

        });
    }

    public static void removeUsers(@NonNull Context context, String... pids) {


        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            long start = System.currentTimeMillis();

            try {
                PEERS peers = PEERS.getInstance(context);
                EVENTS events = EVENTS.getInstance(context);
                peers.setUsersInvisible(pids);

                Gson gson = new Gson();
                DeleteOperation deleteOperation = new DeleteOperation();
                deleteOperation.kind = DeleteOperation.PEERS;
                deleteOperation.pids = pids;

                String content = gson.toJson(deleteOperation, DeleteOperation.class);
                events.delete(content);
            } catch (Throwable e) {
                LogUtils.error(TAG, e);
            } finally {
                LogUtils.info(TAG, " removeUsers finish onStart [" +
                        (System.currentTimeMillis() - start) + "]...");
            }

        });
    }


}

