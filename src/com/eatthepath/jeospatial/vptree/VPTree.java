package com.eatthepath.jeospatial.vptree;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import com.eatthepath.jeospatial.CachingGeospatialPoint;
import com.eatthepath.jeospatial.GeospatialPoint;
import com.eatthepath.jeospatial.GeospatialPointDatabase;
import com.eatthepath.jeospatial.SearchCriteria;
import com.eatthepath.jeospatial.SimpleGeospatialPoint;
import com.eatthepath.jeospatial.util.GeospatialDistanceComparator;
import com.eatthepath.jeospatial.util.SearchResults;

/**
 * <p>A geospatial database that uses a vantage point tree as its storage
 * mechanism.</p>
 * 
 * <p>Vantage point trees (or "vp-trees") are a subclass of metric trees.
 * Vantage point trees use binary space partitioning to recursively divide
 * points among their nodes. Nodes in a vantage point tree have a center point
 * and a distance threshold; points with a distance less than or equal to the
 * threshold are assigned to the "left" child of a node and the others are
 * assigned to the "right" child.</p>
 * 
 * <p>Queries in a vp-tree execute in O(log n) time in the best case, and tree
 * construction takes O(n log n) time.</p>
 * 
 * @author <a href="mailto:jon.chambers@gmail.com">Jon Chambers</a>
 * 
 * @see <a href="http://pnylab.com/pny/papers/vptree/main.html">Peter N. Yianilos' original paper on vp-trees</a>
 */
public class VPTree<E extends GeospatialPoint> implements GeospatialPointDatabase<E> {
    protected class VPNode<T extends GeospatialPoint> {
        private CachingGeospatialPoint center;
        private double threshold;
        
        private VPNode<T> closer;
        private VPNode<T> farther;
        
        private Vector<T> points;
        private final int binSize;
        
        public VPNode(int binSize) {
            this.binSize = binSize;
            this.points = new Vector<T>(this.binSize);
            
            this.center = null;
        }
        
        public VPNode(T[] points, int fromIndex, int toIndex, int binSize) {
            this.binSize = binSize;
            
            if(toIndex - fromIndex <= binSize) {
                // All done! This is a leaf node.
                this.storePoints(points, fromIndex, toIndex);
            } else {
                // We have more points than we want to store in a single leaf
                // node; try to partition the nodes.
                try {
                    this.partition(points, fromIndex, toIndex);
                } catch(PartitionException e) {
                    // Partitioning failed; this is most likely because all of
                    // the points we were given are coincident.
                    this.storePoints(points, fromIndex, toIndex);
                }
            }
        }
        
        protected VPNode<T> getCloserNode() {
            return this.closer;
        }
        
        protected VPNode<T> getFartherNode() {
            return this.farther;
        }
        
        protected GeospatialPoint getCenter() {
            return this.center;
        }
        
        public boolean addAll(Collection<? extends T> points) {
            HashSet<VPNode<T>> nodesToPartition = new HashSet<VPNode<T>>();
            
            for(T point : points) {
                this.add(point, true, nodesToPartition);
            }
            
            // Resolve all of the deferred partitioning
            for(VPNode<T> node : nodesToPartition) {
                try {
                    node.partition();
                } catch(PartitionException e) {
                    // Nothing to do here; this just means some nodes are bigger
                    // than they want to be.
                }
            }
            
            // The tree was definitely modified as long as we were given a
            // non-empty collection of points to add.
            return !points.isEmpty();
        }
        
        public boolean add(T point) {
            return this.add(point, false, null);
        }
        
        protected boolean add(T point, boolean deferPartitioning, Set<VPNode<T>> nodesToPartition) {
            if(this.isLeafNode()) {
                this.points.add(point);
                
                // Should we try to repartition?
                if(this.points.size() > this.binSize) {
                    if(deferPartitioning) {
                        nodesToPartition.add(this);
                    } else {
                        try {
                            this.partition();
                        } catch(PartitionException e) {
                            // Nothing to do here; just hold on to all of our
                            // points.
                        }
                    }
                }
            } else {
                if(this.center.getDistanceTo(point) <= this.threshold) {
                    return this.closer.add(point);
                } else {
                    return this.farther.add(point);
                }
            }
            
            return true;
        }
        
        public boolean contains(T point) {
            if(this.isLeafNode()) {
                return this.points.contains(point);
            } else {
                if(this.center.getDistanceTo(point) <= this.threshold) {
                    return this.closer.contains(point);
                } else {
                    return this.farther.contains(point);
                }
            }
        }
        
        public int size() {
            if(this.isLeafNode()) {
                return this.points.size();
            } else {
                return this.closer.size() + this.farther.size();
            }
        }
        
        private void storePoints(T[] points, int fromIndex, int toIndex) {
            this.points = new Vector<T>(this.binSize);
            
            for(int i = fromIndex; i < toIndex; i++) {
                this.points.add(points[i]);
            }
            
            this.closer = null;
            this.farther = null;
        }
        
        public Collection<T> getPoints() {
            return new Vector<T>(this.points);
        }
        
        protected void partition() throws PartitionException {
            if(!this.isLeafNode()) {
                throw new PartitionException("Cannot partition a non-leaf node.");
            }
            
            if(!this.isEmpty()) {
                @SuppressWarnings("unchecked")
                T[] pointArray = this.points.toArray((T[])Array.newInstance(points.iterator().next().getClass(), 0));
                
                this.partition(pointArray, 0, pointArray.length);
            } else {
                throw new PartitionException("Cannot partition an empty node.");
            }
        }
        
        protected void partition(T[] points, int fromIndex, int toIndex) throws PartitionException {
            // We start by choosing a center point and a distance threshold; the
            // median distance from our center to points in our set is a safe
            // bet.
            this.center = new CachingGeospatialPoint(points[fromIndex]);

            // TODO Consider optimizing this
            java.util.Arrays.sort(points, fromIndex, toIndex,
                    new GeospatialDistanceComparator<T>(this.center));
            
            int medianIndex = (fromIndex + toIndex - 1) / 2;
            double medianDistance = this.center.getDistanceTo(points[medianIndex]);
            
            // Since we're picking a definite median value from the list, we're
            // guaranteed to have at least one point that is closer to or EQUAL TO
            // (via identity) the threshold; what we want to do now is find the
            // first point that's farther away and use that as our partitioning
            // point.
            int partitionIndex = -1;
            
            for(int i = medianIndex + 1; i < toIndex; i++) {
                if(this.center.getDistanceTo(points[i]) > medianDistance) {
                    partitionIndex = i;
                    this.threshold = medianDistance;
                    
                    break;
                }
            }
            
            // Did we find a point that's farther away than the median distance? If
            // so, great! If not, move the threshold closer in and see if we can
            // find a distance that partitions our point set.
            if(partitionIndex == -1) {
                for(int i = medianIndex; i > fromIndex; i--) {
                    if(this.center.getDistanceTo(points[i]) < medianDistance) {
                        partitionIndex = i;
                        this.threshold = this.center.getDistanceTo(points[i]);
                        
                        break;
                    }
                }
            }
            
            // Still nothing? Bail out.
            if(partitionIndex == -1) {
                throw new PartitionException(
                    "No viable partition threshold found (all points have equal distance from center).");
            }
            
            // Okay! Now actually use that partition index.
            this.closer = new VPNode<T>(points, fromIndex, partitionIndex, this.binSize);
            this.farther = new VPNode<T>(points, partitionIndex, toIndex, this.binSize);
            
            // We're definitely not a leaf node now, so clear out our internal
            // point vector (if we had one).
            this.points = null;
        }
        
        /**
         * Indicates whether this is a leaf node.
         * 
         * @return @{code true} if this node is a leaf node or @{code false}
         *         otherwise
         */
        public boolean isLeafNode() {
            return this.closer == null;
        }
        
        public boolean isEmpty() {
            if(this.isLeafNode()) {
                return this.points.isEmpty();
            } else {
                return (this.closer.isEmpty() && this.farther.isEmpty());
            }
        }

        protected void getNearestNeighbors(final GeospatialPoint queryPoint, final SearchResults<T> results) {
            // If this is a leaf node, our job is easy. Offer all of our points
            // to the result set and bail out.
            if(this.isLeafNode()) {
                results.addAll(this.points);
            } else {
                // Descend through the tree recursively.
                boolean searchedCloserFirst;
                double distanceToCenter = this.center.getDistanceTo(queryPoint);
                
                if(distanceToCenter <= this.threshold) {
                    this.closer.getNearestNeighbors(queryPoint, results);
                    searchedCloserFirst = true;
                } else {
                    this.farther.getNearestNeighbors(queryPoint, results);
                    searchedCloserFirst = false;
                }
                
                // ...and now we're on our way back up. Decide if we need to search
                // whichever child we didn't search on the way down.
                if(searchedCloserFirst) {
                    // We've already searched the node that contains points
                    // within our threshold (which also implies that the query
                    // point is inside our threshold); we also want to search
                    // the node beyond our threshold if the distance from the
                    // query point to the most distant match is longer than the
                    // distance from the query point to our threshold, since
                    // there could be a point outside our threshold that's
                    // closer than the most distant match.
                    double distanceToThreshold = this.threshold - distanceToCenter;
                    
                    if(results.getLongestDistanceFromQueryPoint() > distanceToThreshold) {
                        this.farther.getNearestNeighbors(queryPoint, results);
                    }
                } else {
                    // We've already searched the node that contains points
                    // beyond our threshold, and the query point itself is
                    // beyond our threshold. We want to search the
                    // within-threshold node if it's "easier" to get from the
                    // query point to our region than it is to get from the
                    // query point to the most distant match, since there could
                    // be a point within our threshold that's closer than the
                    // most distant match.
                    double distanceToThreshold = distanceToCenter - this.threshold;
                    
                    if(distanceToThreshold <= results.getLongestDistanceFromQueryPoint()) {
                        this.closer.getNearestNeighbors(queryPoint, results);
                    }
                }
            }
        }
        
        protected void getAllWithinRange(final CachingGeospatialPoint queryPoint, final double maxDistance, final SearchCriteria<T> criteria, final Vector<T> results) {
            // If this is a leaf node, just add all of our points to the list if
            // they fall within range and meet the search criteria (if any).
            if(this.isLeafNode()) {
                for(T point : this.points) {
                    if(queryPoint.getDistanceTo(point) <= maxDistance) {
                        if(criteria == null || criteria.matches(point)) {
                            results.add(point);
                        }
                    }
                }
            } else {
                // We want to search whichever of our nodes intersect with the
                // query region, which remains static throughout an
                // "all within range" search.
                double distanceToQueryPoint = this.center.getDistanceTo(queryPoint);
                
                // Does any part of the query region fall within our threshold?
                if(distanceToQueryPoint <= this.threshold + maxDistance) {
                    this.closer.getAllWithinRange(queryPoint, maxDistance, criteria, results);
                }
                
                // Does any part of the query region fall outside of our
                // threshold? Or, put differently, does our region fail to
                // completely enclose the query region?
                if(distanceToQueryPoint + maxDistance > this.threshold) {
                    this.farther.getAllWithinRange(queryPoint, maxDistance, criteria, results);
                }
            }
        }
        
        protected void addPointsToArray(Object[] array) {
            this.addPointsToArray(array, 0);
        }
        
        protected int addPointsToArray(Object[] array, int offset) {
            if(this.isLeafNode()) {
                if(this.isEmpty()) { return 0; }
                
                System.arraycopy(this.points.toArray(), 0, array, offset, this.points.size());
                
                return this.points.size();
            } else {
                int nAddedFromCloser = this.closer.addPointsToArray(array, offset);
                int nAddedFromFarther = this.farther.addPointsToArray(array, offset + nAddedFromCloser);
                
                return nAddedFromCloser + nAddedFromFarther;
            }
        }
        
        public void findNodeContainingPoint(GeospatialPoint p, Deque<VPNode<T>> stack) {
            // First things first; add ourselves to the stack.
            stack.push(this);
            
            // If this is a leaf node, we don't need to do anything else. If
            // it's not a leaf node, recurse!
            if(!this.isLeafNode()) {
                if(this.center.getDistanceTo(p) <= this.threshold) {
                    this.closer.findNodeContainingPoint(p, stack);
                } else {
                    this.farther.findNodeContainingPoint(p, stack);
                }
            }
        }
        
        public void findNode(VPNode<T> node, Deque<VPNode<T>> stack) {
            this.findNodeContainingPoint(node.getCenter(), stack);
        }
        
        public boolean remove(T point) {
            if(this.isLeafNode()) {
                return this.points.remove(point);
            } else {
                throw new IllegalStateException("Cannot remove points from a non-leaf node.");
            }
        }
        
        public void absorbChildren() {
            if(this.isLeafNode()) {
                throw new IllegalStateException("Leaf nodes have no children.");
            }
            
            this.points = new Vector<T>(this.binSize);
            
            if(!this.closer.isLeafNode()) {
                this.closer.absorbChildren();
            }
            
            if(!this.farther.isLeafNode()) {
                this.farther.absorbChildren();
            }
            
            this.points.addAll(this.closer.getPoints());
            this.points.addAll(this.farther.getPoints());
            
            this.closer = null;
            this.farther = null;
        }
        
        public void gatherLeafNodes(List<VPNode<T>> leafNodes) {
            if(this.isLeafNode()) {
                leafNodes.add(this);
            } else {
                this.closer.gatherLeafNodes(leafNodes);
                this.farther.gatherLeafNodes(leafNodes);
            }
        }
    }
    
    private static final int DEFAULT_BIN_SIZE = 32;
    private final int binSize;
    
    private VPNode<E> root;
    
    /**
     * Constructs a new, empty vp-tree with a default node capacity.
     */
    public VPTree() {
        this(DEFAULT_BIN_SIZE);
    }
    
    /**
     * Constructs a new, empty vp-tree with the specified node capacity.
     * 
     * @param nodeCapacity
     *            the maximum number of points to store in a leaf node of the
     *            tree
     */
    public VPTree(int nodeCapacity) {
        this.binSize = nodeCapacity;
        this.root = new VPNode<E>(this.binSize);
    }
    
    /**
     * Constructs a new vp-tree that contains (and indexes) all of the points in
     * the given collection. Nodes of the tree are created with a default
     * capacity.
     * 
     * @param points
     *            the points to use to populate this tree
     */
    public VPTree(Collection<E> points) {
        this(points, DEFAULT_BIN_SIZE);
    }
    
    public VPTree(Collection<E> points, int nodeCapacity) {
        this.binSize = nodeCapacity;
        
        if(!points.isEmpty()) {
            @SuppressWarnings("unchecked")
            E[] pointArray = points.toArray((E[])Array.newInstance(points.iterator().next().getClass(), 0));
            
            this.root = new VPNode<E>(pointArray, 0, pointArray.length, this.binSize);
        } else {
            this.root = new VPNode<E>(this.binSize);
        }
    }

    @Override
    public boolean add(E point) {
        return this.root.add(point);
    }

    @Override
    public boolean addAll(Collection<? extends E> points) {
        return this.root.addAll(points);
    }

    @Override
    public void clear() {
        this.root = new VPNode<E>(this.binSize);
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean contains(Object o) {
        try {
            return this.root.contains((E)o);
        } catch(ClassCastException e) {
            return false;
        }
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for(Object o : c) {
            if(!this.contains(o)) { return false; }
        }
        
        return true;
    }

    @Override
    public boolean isEmpty() {
        return this.root.isEmpty();
    }

    @Override
    public Iterator<E> iterator() {
        return new TreeIterator<E>(this.root);
    }

    @Override
    public boolean remove(Object o) {
        try {
            @SuppressWarnings("unchecked")
            E point = (E)o;
            
            return this.remove(point, false, null);
        } catch(ClassCastException e) {
            // The object we were given wasn't the kind of thing we're storing,
            // so we definitely can't remove it.
            return false;
        }
    }
    
    protected boolean remove(E point, boolean deferPruning, Set<VPNode<E>> nodesToPrune) {
        ArrayDeque<VPNode<E>> stack = new ArrayDeque<VPNode<E>>();
        this.root.findNodeContainingPoint(point, stack);
        
        VPNode<E> node = stack.pop();
        
        boolean pointRemoved = node.remove(point);
        
        if(node.isEmpty()) {
            if(deferPruning) {
                nodesToPrune.add(node);
            } else {
                this.pruneEmptyNode(node);
            }
        }
        
        return pointRemoved;
    }
    
    @Override
    public boolean removeAll(Collection<?> c) {
        boolean anyChanged = false;
        HashSet<VPNode<E>> nodesToPrune = new HashSet<VPNode<E>>();
        
        for(Object o : c) {
            try {
                @SuppressWarnings("unchecked")
                E point = (E)o;
                
                boolean pointRemoved = this.remove(point, true, nodesToPrune);
                anyChanged = anyChanged || pointRemoved;
            } catch(ClassCastException e) {
                // The object wasn't the kind of point we have in this tree;
                // just keep moving.
            }
        }
        
        for(VPNode<E> node : nodesToPrune) {
            // There's an edge case here where both children of a node will be
            // in the pruning set; it's harmless (if inefficient) to try to
            // find/prune a node that's already been pruned, so we don't go too
            // far out of our way to avoid that case.
            this.pruneEmptyNode(node);
        }
        
        return anyChanged;
    }

    protected void pruneEmptyNode(VPNode<E> node) {
        // Only spend time working on this if the node is actually empty; it's
        // harmless to call this method on a non-empty node, though.
        if(node.isEmpty()) {
            ArrayDeque<VPNode<E>> stack = new ArrayDeque<VPNode<E>>();
            this.root.findNode(node, stack);
            
            // Immediately pop the first node off the stack (since we know it's
            // the empty leaf node we were handed as an argument).
            stack.pop();
            
            // Work through the stack until we either have a non-empty parent or
            // we hit the root of the tree.
            while(stack.peek() != null) {
                VPNode<E> parent = stack.pop();
                parent.absorbChildren();
                
                // We're done as soon as we have a non-empty parent.
                if(!parent.isEmpty()) { break; }
            }
        }
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("VP-trees do not support the optional retainAll method.");
    }

    @Override
    public int size() {
        return this.root.size();
    }

    @Override
    public Object[] toArray() {
        Object[] array = new Object[this.size()];
        this.root.addPointsToArray(array);
        
        return array;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T[] toArray(T[] a) {
        int size = this.size();
        
        if(a.length < this.size()) {
            return (T[])java.util.Arrays.copyOf(this.toArray(), size, a.getClass());
        } else {
            System.arraycopy(this.toArray(), 0, a, 0, size);
            
            if(a.length > size) { a[size] = null; }
            
            return a;
        }
    }

    @Override
    public List<E> getNearestNeighbors(GeospatialPoint queryPoint, int maxResults) {
        SearchResults<E> results = new SearchResults<E>(queryPoint, maxResults);
        this.root.getNearestNeighbors(queryPoint, results);
        
        return results.toSortedList();
    }

    @Override
    public List<E> getNearestNeighbors(GeospatialPoint queryPoint, int maxResults, double maxDistance) {
        SearchResults<E> results = new SearchResults<E>(queryPoint, maxResults, maxDistance);
        this.root.getNearestNeighbors(queryPoint, results);
        
        return results.toSortedList();
    }

    @Override
    public List<E> getNearestNeighbors(GeospatialPoint queryPoint, int maxResults, SearchCriteria<E> searchCriteria) {
        SearchResults<E> results = new SearchResults<E>(queryPoint, maxResults, searchCriteria);
        this.root.getNearestNeighbors(queryPoint, results);
        
        return results.toSortedList();
    }

    @Override
    public List<E> getNearestNeighbors(GeospatialPoint queryPoint, int maxResults, double maxDistance, SearchCriteria<E> searchCriteria) {
        SearchResults<E> results = new SearchResults<E>(queryPoint, maxResults, maxDistance, searchCriteria);
        this.root.getNearestNeighbors(queryPoint, results);
        
        return results.toSortedList();
    }

    @Override
    public List<E> getAllNeighborsWithinDistance(GeospatialPoint queryPoint, double maxDistance) {
        return this.getAllNeighborsWithinDistance(queryPoint, maxDistance, null);
    }

    @Override
    public List<E> getAllNeighborsWithinDistance(GeospatialPoint queryPoint, double maxDistance, SearchCriteria<E> searchCriteria) {
        Vector<E> results = new Vector<E>(this.binSize);
        this.root.getAllWithinRange(new CachingGeospatialPoint(queryPoint), maxDistance, searchCriteria, results);
        
        java.util.Collections.sort(results, new GeospatialDistanceComparator<E>(queryPoint));
        
        return results;
    }

    @Override
    public void movePoint(E point, double latitude, double longitude) {
        this.movePoint(point, new SimpleGeospatialPoint(latitude, longitude));
    }

    @Override
    public void movePoint(E point, GeospatialPoint destination) {
        // Moving points can trigger significant structural changes to a
        // tree. If a point's departure from a node would leave that node
        // empty, its parent needs to gather the nodes from its children and
        // potentially repartition itself. If the point's arrival in a node
        // would push that node over the bin size threshold, the node might
        // need to be partitioned. We want to avoid the case where we'd move
        // the point out of a node, regroup things in the parent, and then
        // put the node right back in the same place, so we do some work in
        // advance to see if the old and new positions would fall into the
        // same tree node.
        ArrayDeque<VPNode<E>> sourcePath = new ArrayDeque<VPNode<E>>();
        ArrayDeque<VPNode<E>> destinationPath = new ArrayDeque<VPNode<E>>();
        
        this.root.findNodeContainingPoint(point, sourcePath);
        this.root.findNodeContainingPoint(destination, destinationPath);
        
        if(sourcePath.equals(destinationPath)) {
            // Easy! We expect no structural changes, so we can modify the
            // point directly and immediately.
            point.setLatitude(destination.getLatitude());
            point.setLongitude(destination.getLongitude());
        } else {
            // We don't know that moving the point will cause structural
            // changes, but we have to assume it will.
            this.remove(point);
            
            point.setLatitude(destination.getLatitude());
            point.setLongitude(destination.getLongitude());
            
            this.add(point);
        }
    }
}