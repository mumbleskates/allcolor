import java.util.HashMap;


public class ImplicitKdTree<T> {
    private final int k;
    private final double[] lower;
    private final double[] upper;
    private KdNode<T> head;
    private HashMap<T,KdNode<T>> items = new HashMap<>();


    /**
     * @param k     The dimensionality of the tree
     * @param lower Lower bounds for each dimension
     * @param upper Upper bounds for each dimension
     */
    public ImplicitKdTree(int k, double[] lower, double[] upper) {
        if (k < 1 ||
            lower == null || lower.length != k ||
            upper == null || upper.length != k) {
            throw new IllegalArgumentException("k must be positive and lower & upper must be of length k");
        }
        this.k = k;
        this.lower = lower;
        this.upper = upper;
    }

    public int size() {
        return items.size();
    }

    public double[] get(final T key) {
        return items.get(key).val;
    }

    /**
     * Store or change a value in the k-d tree.
     * @return the old value for this key, or null if none existed
     */
    public double[] put(final T key, final double[] val) {
        double[] oldVal;
        if (val == null || val.length != k) {
            throw new IllegalArgumentException("val must have length " + k + " to match the tree");
        }

        // remove previous node for this key if it exists
        KdNode<T> current = items.get(key);
        if (items == null) {
            oldVal = null;
        } else {
            oldVal = current.val;
            removeNode(current);
        }

        // place a new node in the tree
        if (head == null) {
            // place a new head node
            KdNode<T> newNode = new KdNode<>(
                    key, val,
                    null, 0,
                    (lower[0] + upper[0]) / 2
            );
            head = newNode;
            items.put(key, newNode);
        } else {
            // we already have a tree, traverse down it
            double[] envLower = lower.clone();
            double[] envUpper = upper.clone();
            int depth = 0;
            current = head;
            while (true) {
                int dim = depth % k;
                depth++;
                if (val[dim] < current.mid) {
                    envUpper[dim] = current.mid;
                    // traverse left
                    if (current.left != null) {
                        current = current.left;
                    } else {
                        int nextDim = dim + 1;
                        if (nextDim == k) nextDim = 0;
                        KdNode<T> newNode = new KdNode<>(
                                key, val,
                                current, depth,
                                (envLower[nextDim] + envUpper[nextDim]) / 2
                        );
                        current.left = newNode;
                        items.put(key, newNode);
                        break;
                    }
                } else {
                    envLower[dim] = current.mid;
                    // traverse right
                    if (current.right != null) {
                        current = current.right;
                    } else {
                        int nextDim = dim + 1;
                        if (nextDim == k) nextDim = 0;
                        KdNode<T> newNode = new KdNode<>(
                                key, val,
                                current, depth,
                                (envLower[nextDim] + envUpper[nextDim]) / 2
                        );
                        current.right = newNode;
                        items.put(key, newNode);
                        break;
                    }
                }
            }

            // update parent nodes' maxdepth
            while (current != null) {
                if (current.maxdepth >= depth) {
                    break;
                }
                current.maxdepth = depth;
                current = current.parent;
            }
        }
        return oldVal;
    }

    public double[] remove(final T key) {
        KdNode<T> node = items.remove(key);
        if (node == null) {
            return null;
        } else {
            removeNode(node);
            return node.val;
        }
    }

    public NearestResult<T> nearest(final double[] val) {
        if (val == null || val.length != k) {
            throw new IllegalArgumentException("val must have length " + k + " to match the tree");
        }
        if (head == null) {
            return null;
        }
        NearestResult<T> result = new NearestResult<>(null, null, Double.POSITIVE_INFINITY);
        search(val, head, 0, result);
        return result;
    }

    public NearestResult<T> nearest(final double[] val, final double maxSqDistance) {
        if (val == null || val.length != k) {
            throw new IllegalArgumentException("val must have length " + k + " to match the tree");
        }
        if (head == null || maxSqDistance <= 0 || Double.isNaN(maxSqDistance)) {
            return null;
        }
        NearestResult<T> result = new NearestResult<>(null, null, maxSqDistance);
        search(val, head, 0, result);
        if (result.key == null) {
            // no result was found
            return null;
        } else {
            return result;
        }
    }


    private void removeNode(final KdNode<T> node) {
        KdNode<T> popped = node.popDeepest();
        if (popped == node) {
            // this node is being removed
            KdNode<T> parent = node.parent;
            if (parent == null) {
                // this node was head
                head = null;
            } else {
                if (parent.left == node) {
                    parent.left = null;
                } else {
                    parent.right = null;
                }
            }
        } else {
            // replacement case
            node.key = popped.key;
            node.val = popped.val;
            items.put(node.key, node);
        }
    }

    private void search(final double[] val, final KdNode<T> node, int depth, final NearestResult<T> result) {
        // calculate sqDistance to this node
        double nodeSqDistance = 0;
        for (int i = 0; i < k; i++) {
            double diff = (node.val[i] - val[i]);
            nodeSqDistance += diff * diff;
        }
        // update best
        if (nodeSqDistance < result.sqDistance) {
            result.key = node.key;
            result.val = node.val;
        }
        // traverse downwards
        int dim = depth % k;
        double mid = node.mid;
        depth++;
        // traverse near side first
        double mid_diff = val[dim] - mid;  // distance to splitting plane
        if (mid_diff < 0) {  // val is left of splitting plane
            if (node.left != null) {
                search(val, node.left, depth, result);
            }
            // traverse the other side if needed
            if (node.right != null && mid_diff * mid_diff < result.sqDistance) {
                search(val, node.right, depth, result);
            }
        } else {  // val is right of splitting plane
            if (node.right != null) {
                search(val, node.right, depth, result);
            }
            // traverse the other side if needed
            if (node.left != null && mid_diff * mid_diff < result.sqDistance) {
                search(val, node.left, depth, result);
            }
        }
    }


    private static class KdNode<T> {
        T key;
        double[] val;
        int maxdepth;
        final double mid;
        final KdNode<T> parent;
        KdNode<T> left;
        KdNode<T> right;

        KdNode(T key, double[] val, KdNode<T> parent, int depth, double mid) {
            this.key = key;
            this.val = val;
            this.parent = parent;
            this.maxdepth = depth;
            this.mid = mid;
        }

        KdNode<T> popDeepest() {
            if (left == null) {
                if (right == null) {
                    // this is a leaf node
                    // before returning, adjust tree depths above us
                    KdNode<T> current = this;
                    KdNode<T> parent = this.parent;
                    final int newDepth = this.maxdepth - 1;
                    while (parent != null) {
                        if (parent.left == current) {
                            // we can reduce parent's depth but not below the depth of the other side
                            if (parent.right == null || parent.right.maxdepth <= newDepth) {
                                parent.maxdepth = newDepth;
                            } else {
                                break;
                            }
                        } else {
                            // same on other side
                            if (parent.left == null || parent.left.maxdepth <= newDepth) {
                                parent.maxdepth = newDepth;
                            } else {
                                break;
                            }
                        }
                        // traverse upwards
                        current = parent;
                        parent = parent.parent;
                    }
                    // this node will be removed
                    return this;
                }
            } else {
                if (right == null || right.maxdepth < this.maxdepth) {
                    // left is deeper/only
                    final KdNode<T> result = left.popDeepest();
                    if (result == left) {
                        left = null;
                    }
                    return result;
                }
            }
            // right is deeper/only
            final KdNode<T> result = right.popDeepest();
            if (result == right) {
                right = null;
            }
            return result;
        }
    }

    public static final class NearestResult<T> {
        public T key;
        public double[] val;
        public double sqDistance;

        public NearestResult(T key, double[] val, double sqDistance) {
            this.key = key;
            this.val = val;
            this.sqDistance = sqDistance;
        }
    }
}
