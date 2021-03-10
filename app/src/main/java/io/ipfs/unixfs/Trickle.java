package io.ipfs.unixfs;

import android.util.Pair;

import androidx.annotation.NonNull;

import io.ipfs.format.Node;
import io.ipfs.merkledag.DagBuilderHelper;
import unixfs.pb.UnixfsProtos;

public class Trickle {

    public static int depthRepeat = 4;

    public static Node Layout(@NonNull DagBuilderHelper db) {
        DagBuilderHelper.FSNodeOverDag newRoot =
                db.NewFSNodeOverDag(UnixfsProtos.Data.DataType.File);
        Pair<Node, Long> result = fillTrickleRec(db, newRoot, -1);

        Node root = result.first;
        db.Add(root);
        return root;
    }


    public static Pair<Node, Long> fillTrickleRec(@NonNull DagBuilderHelper db,
                                                  @NonNull DagBuilderHelper.FSNodeOverDag node,
                                                  int maxDepth) {
        // Always do this, even in the base case
        db.FillNodeLayer(node);


        for (int depth = 1; maxDepth == -1 || depth < maxDepth; depth++) {
            if (db.Done()) {
                break;
            }

            for (int repeatIndex = 0; repeatIndex < depthRepeat && !db.Done(); repeatIndex++) {

                Pair<Node, Long> result = fillTrickleRec(db, db.NewFSNodeOverDag(
                        UnixfsProtos.Data.DataType.File), depth);

                node.AddChild(result.first, result.second, db);
            }
        }
        Node filledNode = node.Commit();

        return Pair.create(filledNode, node.FileSize());
    }

}
