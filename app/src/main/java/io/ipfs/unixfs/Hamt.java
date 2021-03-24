package io.ipfs.unixfs;

import androidx.annotation.NonNull;

import java.util.Objects;

import io.ipfs.format.Node;
import io.ipfs.format.ProtoNode;
import io.ipfs.merkledag.DagService;
import io.protos.unixfs.UnixfsProtos;

public class Hamt {


    @NonNull
    public static Shard NewHamtFromDag(@NonNull DagService dagService, @NonNull Node nd) {

        ProtoNode pn = (ProtoNode) nd;
        Objects.requireNonNull(pn);

        FSNode fsn = FSNode.FSNodeFromBytes(pn.getData());

        if (fsn.Type() != UnixfsProtos.Data.DataType.HAMTShard) {
            throw new RuntimeException();
        }

        int size = (int) fsn.Fanout();
        Shard ds = Shard.makeShard(dagService, size);
        ds.childer.makeChilder(fsn.Data(), pn.Links());
        ds.cid = pn.Cid();
        ds.builder = pn.CidBuilder();

        return ds;
    }
}