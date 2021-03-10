package io.ipfs.utils;

import androidx.annotation.NonNull;

import java.util.List;

import io.ipfs.Closeable;
import io.ipfs.Storage;
import io.ipfs.blockservice.BlockService;
import io.ipfs.blockstore.Blockstore;
import io.ipfs.cid.Cid;
import io.ipfs.cid.Prefix;
import io.ipfs.exchange.Interface;
import io.ipfs.format.Link;
import io.ipfs.format.Node;
import io.ipfs.format.ProtoNode;
import io.ipfs.merkledag.DagService;
import io.ipfs.multihash.Multihash;
import io.ipfs.offline.Exchange;
import io.ipfs.path.Path;
import io.ipfs.unixfs.Directory;
import io.ipfs.unixfs.FSNode;

public class Stream {

    public static final int File = 2;
    public static final int Dir = 1;
    public static final int Symlink = 4;
    public static final int NotKnown = 8;

    private static final String TAG = Stream.class.getSimpleName();

    public static Adder getFileAdder(@NonNull Storage storage, @NonNull Closeable closeable) {


        Blockstore bs = Blockstore.NewBlockstore(storage);
        Interface exchange = new Exchange(bs);
        BlockService blockservice = BlockService.New(bs, exchange);
        DagService dagService = new DagService(blockservice);
        Adder fileAdder = Adder.NewAdder(closeable, dagService);

        Prefix prefix = Node.PrefixForCidVersion(1);

        prefix.MhType = Multihash.Type.sha2_256.index;
        prefix.MhLength = -1;

        fileAdder.Chunker = "size-262144";
        fileAdder.RawLeaves = false;
        fileAdder.NoCopy = false;
        fileAdder.CidBuilder = prefix;


        return fileAdder;
    }

    public static boolean IsDir(@NonNull Storage storage,
                                @NonNull Closeable closeable,
                                @NonNull String path) {

        Blockstore bs = Blockstore.NewBlockstore(storage);
        Interface exchange = new Exchange(bs);
        BlockService blockservice = BlockService.New(bs, exchange);
        DagService dagService = new DagService(blockservice);

        io.ipfs.format.Node node = Resolver.ResolveNode(closeable, dagService, Path.New(path));

        Directory dir = Directory.NewDirectoryFromNode(dagService, node);
        return dir != null;
    }

    public static String CreateEmptyDir(@NonNull Storage storage, @NonNull Closeable closeable) {

        Adder fileAdder = getFileAdder(storage, closeable);

        Node nd = fileAdder.CreateEmptyDir();
        return nd.Cid().String();
    }


    public static String AddLinkToDir(@NonNull Storage storage, @NonNull Closeable closeable,
                                      @NonNull String dir, @NonNull String name, @NonNull String link) {

        Adder fileAdder = getFileAdder(storage, closeable);

        Blockstore bs = Blockstore.NewBlockstore(storage);
        Interface exchange = new Exchange(bs);
        BlockService blockservice = BlockService.New(bs, exchange);
        DagService dagService = new DagService(blockservice);

        io.ipfs.format.Node dirNode = Resolver.ResolveNode(closeable, dagService, Path.New(dir));
        io.ipfs.format.Node linkNode = Resolver.ResolveNode(closeable, dagService, Path.New(link));

        Node nd = fileAdder.AddLinkToDir(dirNode, name, linkNode);
        return nd.Cid().String();

    }

    public static String RemoveLinkFromDir(@NonNull Storage storage, @NonNull Closeable closeable,
                                           @NonNull String dir, @NonNull String name) {

        Adder fileAdder = getFileAdder(storage, closeable);

        Blockstore bs = Blockstore.NewBlockstore(storage);
        Interface exchange = new Exchange(bs);
        BlockService blockservice = BlockService.New(bs, exchange);
        DagService dagService = new DagService(blockservice);

        io.ipfs.format.Node dirNode = Resolver.ResolveNode(closeable, dagService, Path.New(dir));

        Node nd = fileAdder.RemoveChild(dirNode, name);
        return nd.Cid().String();

    }

    public static void Ls(@NonNull Storage storage, @NonNull LinkCloseable closeable,
                          @NonNull String path, boolean resolveChildren) {


        Blockstore bs = Blockstore.NewBlockstore(storage);
        Interface exchange = new Exchange(bs);
        BlockService blockservice = BlockService.New(bs, exchange);
        DagService dagService = new DagService(blockservice);


        io.ipfs.format.Node node = Resolver.ResolveNode(closeable, dagService, Path.New(path));

        Directory dir = Directory.NewDirectoryFromNode(dagService, node);

        if (dir == null) {
            lsFromLinks(closeable, dagService, node.getLinks(), resolveChildren);
        } else {
            lsFromLinksAsync(closeable, dagService, dir, resolveChildren);
        }

    }

    private static void lsFromLinksAsync(@NonNull LinkCloseable closeable,
                                         @NonNull DagService dagService,
                                         @NonNull Directory dir,
                                         boolean resolveChildren) {

        List<Link> links = dir.GetNode().getLinks();
        for (Link link : links) {
            processLink(closeable, dagService, link, resolveChildren);
        }
    }

    private static void lsFromLinks(@NonNull LinkCloseable closeable,
                                    @NonNull DagService dagService,
                                    @NonNull List<Link> links,
                                    boolean resolveChildren) {
        for (Link link : links) {
            processLink(closeable, dagService, link, resolveChildren);
        }
    }

    private static void processLink(@NonNull LinkCloseable closeable,
                                    @NonNull DagService dagService,
                                    @NonNull Link link,
                                    boolean resolveChildren) {

        String name = link.getName();
        String hash = link.getCid().String();
        long size = link.getSize();
        Cid cid = link.getCid();

        if (cid.Type() == Cid.Raw) {
            closeable.info(name, hash, size, File);
        } else if (cid.Type() == Cid.DagProtobuf) {
            if (!resolveChildren) {
                closeable.info(name, hash, size, NotKnown);
            } else {

                Node linkNode = link.GetNode(closeable, dagService);
                if (linkNode instanceof ProtoNode) {
                    ProtoNode pn = (ProtoNode) linkNode;
                    FSNode d = FSNode.FSNodeFromBytes(pn.getData());
                    int type = NotKnown;
                    switch (d.Type()) {
                        case File:
                        case Raw:
                            type = File;
                            break;
                        case Symlink:
                            type = Symlink;
                            break;
                        default:
                            type = Dir;
                    }
                    size = d.FileSize();
                    closeable.info(name, hash, size, type);

                }
            }
        }
    }
}
