package com.jarvis.assistant.automation.accessibility

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException

class BoundedTreeTraversalTest {
    private class Node(val id:Int, val children:MutableList<Node> = mutableListOf())
    @Test fun wideTreeAcquiresAtMostBudgetAndReleasesOwnedChildren() {
        val root=Node(0); repeat(10_000){root.children+=Node(it+1)}
        val released=mutableListOf<Node>(); var gets=0
        val r=traverseBoundedTree(root,maxNodes=16,now={0},childCount={it.children.size},
            child={n,i->gets++;n.children[i]},release={released+=it}){}
        assertTrue(r.truncated);assertEquals(16,r.visited);assertEquals(15,gets)
        assertEquals(15,released.size);assertFalse(released.contains(root))
    }
    @Test fun stalePropertyReleasesUnvisitedQueue() {
        val root=Node(0,mutableListOf(Node(1),Node(2),Node(3)))
        val released=mutableListOf<Int>()
        val r=traverseBoundedTree(root,now={0},childCount={it.children.size},child={n,i->n.children[i]},
            release={released+=it.id}){if(it.id==1)error("stale")}
        assertTrue(r.failed); assertEquals(listOf(1,2,3),released)
    }
    @Test fun cyclicAndDeepTreesCannotRunForever() {
        val root=Node(0); var p=root
        repeat(1000) { val next=Node(it+1);p.children+=next;p=next };p.children+=root
        val r=traverseBoundedTree(root,maxDepth=4,now={0},childCount={it.children.size},child={n,i->n.children[i]},release={}){}
        assertTrue(r.truncated);assertEquals(5,r.visited)
    }
    @Test fun timedOutTraversalReleasesPendingNodes() {
        val root=Node(0,mutableListOf(Node(1),Node(2)));var time=0L;val released=mutableListOf<Int>()
        val r=traverseBoundedTree(root,maxMillis=10,now={time},childCount={it.children.size},child={n,i->n.children[i]},
            release={released+=it.id}){if(it.id==1)time=20}
        assertTrue(r.truncated);assertEquals(listOf(1,2),released)
    }
    @Test fun cancellationPropagatesAfterCleanup() {
        val root=Node(0,mutableListOf(Node(1),Node(2)));val released=mutableListOf<Int>()
        try {
            traverseBoundedTree(root,now={0},childCount={it.children.size},child={n,i->n.children[i]},
                release={released+=it.id}){if(it.id==1)throw CancellationException()}
            fail("Cancellation must propagate")
        } catch(_:CancellationException) { assertEquals(listOf(1,2),released) }
    }
    @Test fun depthFirstOrderPreservesLegacyElementOrder() {
        val root=Node(0,mutableListOf(Node(1,mutableListOf(Node(2))),Node(3)))
        val ids=mutableListOf<Int>()
        traverseBoundedTree(root,depthFirst=true,now={0},childCount={it.children.size},child={n,i->n.children[i]},release={}){ids+=it.id}
        assertEquals(listOf(0,1,2,3),ids)
    }
    @Test fun retainedIdentityCannotBeReusedByANativeStyleNodePoolMidTraversal() {
        class Handle(var id: Int)
        val tree = mapOf(0 to listOf(1, 2), 1 to listOf(3), 2 to listOf(4))
        val pool = java.util.ArrayDeque<Handle>()
        val visited = mutableListOf<Int>()
        var released = 0
        val result = traverseBoundedTree(Handle(0), now = { 0 },
            childCount = { tree[it.id].orEmpty().size },
            child = { n, i ->
                val id = tree.getValue(n.id)[i]
                (pool.pollFirst() ?: Handle(id)).also { it.id = id }
            }, release = { released++; pool.addLast(it) }) { visited += it.id }
        assertFalse(result.failed)
        assertFalse(result.truncated)
        assertEquals(listOf(0, 1, 2, 3, 4), visited)
        assertEquals(4, released)
    }

}
