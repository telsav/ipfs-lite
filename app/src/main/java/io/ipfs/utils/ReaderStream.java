package io.ipfs.utils;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;


public class ReaderStream extends InputStream {

    private final Reader reader;
    private int position = 0;
    private byte[] data = null;

    public ReaderStream(@NonNull Reader reader) {
        this.reader = reader;
    }

    @Override
    public int available() {
        long size = reader.getSize();
        return (int) size;
    }


    @Override
    public int read() throws IOException {

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
            throw new IOException(e);
        }
    }

    private void invalidate() {
        position = 0;
        data = null;
    }


    private boolean preLoad() {
        data = reader.loadNextData();
        return data != null;
    }


}