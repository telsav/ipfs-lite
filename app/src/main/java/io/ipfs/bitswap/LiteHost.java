package io.ipfs.bitswap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import io.Closeable;
import io.ipfs.ClosedException;
import io.ipfs.ProtocolNotSupported;
import io.ipfs.cid.Cid;
import io.libp2p.host.Host;
import io.libp2p.network.Stream;
import io.libp2p.network.StreamHandler;
import io.libp2p.peer.PeerID;
import io.libp2p.protocol.Protocol;
import io.libp2p.routing.ContentRouting;
import io.libp2p.routing.Providers;

public class LiteHost implements BitSwapNetwork {

    @NonNull
    private final Host host;

    @Nullable
    private final ContentRouting contentRouting;
    private final List<Protocol> protocols = new ArrayList<>();

    private LiteHost(@NonNull Host host,
                     @Nullable ContentRouting contentRouting,
                     @NonNull List<Protocol> protos) {
        this.host = host;
        this.contentRouting = contentRouting;

        this.protocols.addAll(protos);
    }

    public static BitSwapNetwork NewLiteHost(@NonNull Host host,
                                             @Nullable ContentRouting contentRouting,
                                             @NonNull List<Protocol> protocols) {
        return new LiteHost(host, contentRouting, protocols);
    }

    @Override
    public boolean ConnectTo(@NonNull Closeable closeable, @NonNull PeerID peer, boolean protect) throws ClosedException {
        return host.Connect(closeable, peer, protect);
    }


    @Override
    public void SetDelegate(@NonNull Receiver receiver) {

        for (Protocol protocol : protocols) {
            host.SetStreamHandler(protocol, new StreamHandler() {
                @Override
                public boolean gate(@NonNull PeerID peerID) {
                    return receiver.GatePeer(peerID);
                }

                @Override
                public void error(@NonNull Stream stream) {
                    PeerID peer = stream.RemotePeer();
                    String error = stream.GetError();
                    if (error != null) {
                        receiver.ReceiveError(peer, stream.Protocol(), error);
                    }
                }

                @Override
                public void message(@NonNull Stream stream) {

                    PeerID peer = stream.RemotePeer();
                    try {
                        byte[] data = stream.GetData();
                        BitSwapMessage received = BitSwapMessage.fromData(data);
                        receiver.ReceiveMessage(peer, stream.Protocol(), received);

                    } catch (Throwable throwable) {
                        receiver.ReceiveError(peer, stream.Protocol(),
                                "" + throwable.getMessage());
                    }
                }
            });
        }

    }

    @Override
    public PeerID Self() {
        return host.Self();
    }

    @NonNull
    @Override
    public List<PeerID> getPeers() {
        return host.getPeers();
    }


    @Override
    public void WriteMessage(@NonNull Closeable closeable, @NonNull PeerID peer, @NonNull BitSwapMessage message) throws ClosedException, ProtocolNotSupported {

        byte[] data = message.ToNetV1();
        long res = host.WriteMessage(closeable, peer, protocols, data);
        if (Objects.equals(data.length, res)) {
            throw new RuntimeException("Message not fully written");
        }

    }


    @Override
    public void WriteMessage(@NonNull Closeable closeable,
                             @NonNull PeerID peer,
                             @NonNull Protocol protocol,
                             @NonNull BitSwapMessage message) throws ClosedException, ProtocolNotSupported {
        byte[] data = message.ToNetV1();
        long res = host.WriteMessage(closeable, peer, Collections.singletonList(protocol), data);
        if (Objects.equals(data.length, res)) {
            throw new RuntimeException("Message not fully written");
        }

    }


    @Override
    public void FindProvidersAsync(@NonNull Providers providers, @NonNull Cid cid, int number) throws ClosedException {
        if (contentRouting != null) {
            contentRouting.FindProvidersAsync(providers, cid, number);
        }
    }

    @Override
    public void Provide(@NonNull Closeable closeable, @NonNull Cid cid) {
        throw new RuntimeException("not supported");
    }
}
