package dev.jediscore.datastructures;

import java.util.ArrayList;
import java.util.List;

/**
 * A quicklist: a list stored as a sequence of {@link Listpack} nodes, each
 * capped at a fixed number of entries.
 *
 * <p>This mirrors Redis's quicklist design — a linked list of listpack nodes —
 * which keeps push/pop at the ends O(1) amortised while still packing elements
 * compactly within each node (far less per-element overhead than a node-per-
 * element linked list). It is the large encoding a {@link ListValue} converts to
 * once it outgrows a single listpack.
 *
 * <p>Index operations walk the nodes accumulating counts; with a bounded node
 * size this is the same access pattern Redis uses.
 */
public final class Quicklist implements SequenceStore {

    private final int nodeCapacity;
    private final List<Listpack> nodes = new ArrayList<>();
    private int total;

    /**
     * Creates an empty quicklist.
     *
     * @param nodeCapacity the maximum entries per listpack node
     */
    public Quicklist(int nodeCapacity) {
        this.nodeCapacity = nodeCapacity;
    }

    @Override
    public int size() {
        return total;
    }

    @Override
    public void addFirst(byte[] value) {
        if (nodes.isEmpty() || nodes.get(0).count() >= nodeCapacity) {
            nodes.add(0, new Listpack());
        }
        nodes.get(0).insert(0, value);
        total++;
    }

    @Override
    public void addLast(byte[] value) {
        if (nodes.isEmpty() || lastNode().count() >= nodeCapacity) {
            nodes.add(new Listpack());
        }
        lastNode().add(value);
        total++;
    }

    @Override
    public byte[] removeFirst() {
        Listpack first = nodes.get(0);
        byte[] head = first.removeFirst();
        if (first.count() == 0) {
            nodes.remove(0);
        }
        total--;
        return head;
    }

    @Override
    public byte[] removeLast() {
        Listpack last = lastNode();
        byte[] tail = last.removeLast();
        if (last.count() == 0) {
            nodes.remove(nodes.size() - 1);
        }
        total--;
        return tail;
    }

    @Override
    public byte[] get(int index) {
        long[] loc = locate(index);
        return nodes.get((int) loc[0]).get((int) loc[1]);
    }

    @Override
    public void set(int index, byte[] value) {
        long[] loc = locate(index);
        nodes.get((int) loc[0]).set((int) loc[1], value);
    }

    @Override
    public void insert(int index, byte[] value) {
        if (index == total) {
            addLast(value);
            return;
        }
        long[] loc = locate(index);
        nodes.get((int) loc[0]).insert((int) loc[1], value);
        total++;
    }

    @Override
    public void removeAt(int index) {
        long[] loc = locate(index);
        int nodeIdx = (int) loc[0];
        Listpack node = nodes.get(nodeIdx);
        node.removeAt((int) loc[1]);
        if (node.count() == 0) {
            nodes.remove(nodeIdx);
        }
        total--;
    }

    @Override
    public List<byte[]> toList() {
        List<byte[]> out = new ArrayList<>(total);
        for (Listpack node : nodes) {
            out.addAll(node.toList());
        }
        return out;
    }

    private Listpack lastNode() {
        return nodes.get(nodes.size() - 1);
    }

    /** Resolves a global index to {@code [nodeIndex, offsetWithinNode]}. */
    private long[] locate(int index) {
        if (index < 0 || index >= total) {
            throw new IndexOutOfBoundsException("index " + index + " of " + total);
        }
        int remaining = index;
        for (int n = 0; n < nodes.size(); n++) {
            int count = nodes.get(n).count();
            if (remaining < count) {
                return new long[] {n, remaining};
            }
            remaining -= count;
        }
        throw new IllegalStateException("index resolution failed");
    }
}
