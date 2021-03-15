package io.ipfs.cid;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

import io.ipfs.multihash.Multihash;

public class Prefix implements Builder {
    public long Version;
    public long Codec;
    public long MhType;
    public long MhLength;

    public Prefix(long codec, long mhLength, long mhType, long version) {
        Version = version;
        Codec = codec;
        MhType = mhType;
        MhLength = mhLength;
    }

    @Override
    public Cid Sum(byte[] data) {
        long length = MhLength;
        if (MhType == Cid.IDENTITY) {
            length = -1;
        }

        if (Version == 0 && (MhType != Multihash.Type.sha2_256.index) ||
                (MhLength != 32 && MhLength != -1)) {

            throw new RuntimeException("invalid v0 prefix");
        }
        if (MhType != Multihash.Type.sha2_256.index) {
            throw new RuntimeException("todo");
        }
        try {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = Encode(digest.digest(data), MhType);

            switch ((int) Version) {
                case 0:
                    return Cid.NewCidV0(hash);
                case 1:
                    return Cid.NewCidV1(Codec, hash);
                default:
                    throw new RuntimeException("invalid cid version");
            }

        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }

    }

    byte[] Encode(byte[] buf, long code) {

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Multihash.putUvarint(out, code);
            Multihash.putUvarint(out, buf.length);
            out.write(buf);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long GetCodec() {
        return Codec;
    }

    @Override
    public Builder WithCodec(long codec) {
        if (codec == this.Codec) {
            return this;
        }
        Codec = codec;
        if (codec != Cid.DagProtobuf) {
            Version = 1;
        }
        return this;
    }

    public static Prefix PrefixFromBytes(byte[] buf) {

        try (InputStream inputStream = new ByteArrayInputStream(buf)) {
            long version = Multihash.readVarint(inputStream);
            if (version != 1) {
                throw new Exception("invalid version");
            }
            long codec = Multihash.readVarint(inputStream);
            if (!(codec == Cid.DagProtobuf || codec == Cid.Raw || codec == Cid.Libp2pKey)) {
                throw new Exception("not supported codec");
            }

            long mhtype = Multihash.readVarint(inputStream);

            long mhlen = Multihash.readVarint(inputStream);

            return new Prefix(codec, mhlen, mhtype, version);


        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    public byte[] Bytes() {

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Multihash.putUvarint(out, Version);
            Multihash.putUvarint(out, Codec);
            Multihash.putUvarint(out, MhType);
            Multihash.putUvarint(out, MhLength);
            return out.toByteArray();
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }
}
