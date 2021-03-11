package io.ipfs.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

import io.ipfs.Closeable;
import io.ipfs.LogUtils;
import io.ipfs.blockservice.BlockService;
import io.ipfs.blockstore.Blockstore;
import io.ipfs.exchange.Interface;
import io.ipfs.merkledag.DagService;

public class Reader implements java.io.Closeable {
    private static final String TAG = Reader.class.getSimpleName();
    private final DagReader dagReader;
    private int position = 0;
    private byte[] data = null;


    private Reader(@NonNull DagReader dagReader) {
        this.dagReader = dagReader;
    }

    public static Reader getReader(@NonNull Closeable closeable,
                                   @NonNull Blockstore blockstore,
                                   @NonNull Interface exchange,
                                   @NonNull String cid) {
        BlockService blockservice = BlockService.New(blockstore, exchange);
        DagService dags = new DagService(blockservice);
        io.ipfs.format.Node top = Resolver.ResolveNode(closeable, dags, Path.New(cid));

        DagReader dagReader = DagReader.NewDagReader(closeable, top, dags);

        return new Reader(dagReader);
    }

    public long getSize() {
        return dagReader.getSize();
    }

    public int read() {

        try {
            if (data == null) {
                invalidate();
                preLoad();
            }
            if (data == null) {
                return -1;
            }
            if (position < data.length) {
                byte value = data[position];
                position++;
                return (value & 0xff);
            } else {
                invalidate();
                if (preLoad()) {
                    byte value = data[position];
                    position++;
                    return (value & 0xff);
                } else {
                    return -1;
                }
            }


        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void invalidate() {
        position = 0;
        data = null;
    }

    private boolean preLoad() {

        data = loadNextData();

        return data != null;
    }

    public byte[] load(int size) {
        ByteBuffer buf = ByteBuffer.allocate(size);

        for (int i = 0; i < size; i++) {
            int val = read();
            if (val < 0) {
                break;
            }
            buf.put((byte) (val & 0xff));
        }
        return buf.array();
    }


    public void close() {
        try {
            this.dagReader.close();
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
    }

    public byte[] readAt(long position, int size) {
        seek(position);
        return load(size);
    }

    public void seek(long position) {
        dagReader.Seek(position);
    }

    @Nullable
    public byte[] loadNextData() {
        return this.dagReader.loadNextData();
    }
}
