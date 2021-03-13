package io.libp2p.routing;

import androidx.annotation.NonNull;

import io.Closeable;
import io.ipfs.cid.Cid;

public interface ContentRouting {
    void FindProvidersAsync(@NonNull Providers providers, @NonNull Cid cid, int number);

    void Provide(@NonNull Closeable closeable, @NonNull Cid cid);
}