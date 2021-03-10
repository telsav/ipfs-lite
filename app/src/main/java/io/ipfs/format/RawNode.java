package io.ipfs.format;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import io.ipfs.blocks.BasicBlock;
import io.ipfs.blocks.Block;
import io.ipfs.cid.Builder;
import io.ipfs.cid.Cid;

public class RawNode implements Node {

    private final Block block;


    public RawNode(@NonNull Block block) {
        this.block = block;
    }

    public static Node NewRawNode(byte[] data) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            // TODO check if working
            Cid cid = Cid.NewCidV1(Cid.Raw, hash);
            Block blk = BasicBlock.NewBlockWithCid(cid, data);

            return new RawNode(blk);
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }

    }

    public static Node NewRawNodeWPrefix(byte[] data, Builder builder) {

        builder = builder.WithCodec(Cid.Raw);
        Cid cid = builder.Sum(data);

        Block blk = BasicBlock.NewBlockWithCid(cid, data);

        return new RawNode(blk);

    }

    @Override
    public void SetCidBuilder(@Nullable Builder builder) {
        throw new RuntimeException("TODO");
    }

    @Override
    public long Size() {
        // TODO
        return getData().length;
    }

    @Override
    public List<Link> getLinks() {
        return new ArrayList<>();
    }

    @Override
    public Cid Cid() {
        return block.Cid();
    }

    @Override
    public byte[] getData() {
        return block.RawData();
    }

    @Override
    public byte[] RawData() {
        return block.RawData();
    }

}
