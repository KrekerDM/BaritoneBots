package io.github.krekerdm.baritonebots.manager.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskQueueTest {
    private static QueueEntry e(String type) {
        return QueueEntry.of(type, new JsonObject(), 0, null, "panel");
    }

    private static List<String> types(TaskQueue q) {
        return q.items().stream().map(QueueEntry::type).toList();
    }

    @Test
    void appendKeepsOrder() {
        TaskQueue q = new TaskQueue();
        q.add(List.of(e("a"), e("b")), TaskQueue.Mode.APPEND);
        q.add(List.of(e("c")), TaskQueue.Mode.APPEND);
        assertEquals(List.of("a", "b", "c"), types(q));
    }

    @Test
    void frontPutsBatchFirstInItsOwnOrder() {
        TaskQueue q = new TaskQueue();
        q.add(List.of(e("a")), TaskQueue.Mode.APPEND);
        q.add(List.of(e("x"), e("y")), TaskQueue.Mode.FRONT);
        assertEquals(List.of("x", "y", "a"), types(q));
    }

    @Test
    void replaceDropsQueuedAndReturnsRunning() {
        TaskQueue q = new TaskQueue();
        q.add(List.of(e("a"), e("b")), TaskQueue.Mode.APPEND);
        QueueEntry running = q.poll();
        q.start(running, 1);
        QueueEntry toCancel = q.add(List.of(e("z")), TaskQueue.Mode.REPLACE);
        assertSame(running, toCancel);
        assertEquals(List.of("z"), types(q));
        assertSame(running, q.abortCurrent());
        assertNull(q.current());
    }

    @Test
    void replaceWhenIdleReturnsNull() {
        TaskQueue q = new TaskQueue();
        assertNull(q.add(List.of(e("z")), TaskQueue.Mode.REPLACE));
    }

    @Test
    void finishIgnoresStaleIds() {
        TaskQueue q = new TaskQueue();
        QueueEntry a = e("a");
        q.start(a, 5);
        assertNull(q.finish("other"));
        assertSame(a, q.current());
        assertSame(a, q.finish(a.id()));
        assertTrue(q.isIdle());
    }

    @Test
    void reorderPutsListedFirstAndKeepsTheRest() {
        TaskQueue q = new TaskQueue();
        QueueEntry a = e("a");
        QueueEntry b = e("b");
        QueueEntry c = e("c");
        QueueEntry d = e("d");
        q.add(List.of(a, b, c, d), TaskQueue.Mode.APPEND);
        q.reorder(List.of(c.id(), "unknown", a.id()));
        assertEquals(List.of("c", "a", "b", "d"), types(q));
    }

    @Test
    void removeQueuedAndRemoveIf() {
        TaskQueue q = new TaskQueue();
        QueueEntry a = e("a");
        QueueEntry b = QueueEntry.of("b", new JsonObject(), 0, null, "scenario:r1");
        QueueEntry c = QueueEntry.of("c", new JsonObject(), 0, null, "scenario:r1");
        q.add(List.of(a, b, c), TaskQueue.Mode.APPEND);
        assertSame(a, q.removeQueued(a.id()));
        assertNull(q.removeQueued(a.id()));
        assertEquals(2, q.removeIf(x -> x.hasOrigin("scenario:r1")).size());
        assertTrue(q.isIdle());
    }

    @Test
    void insertAfterLeadingSkipsMatchingHead() {
        TaskQueue q = new TaskQueue();
        q.add(List.of(e("recover"), e("a")), TaskQueue.Mode.APPEND);
        q.insertAfterLeading(x -> x.type().equals("recover"), e("deposit"));
        assertEquals(List.of("recover", "deposit", "a"), types(q));
    }

    @Test
    void modeParsing() {
        assertEquals(TaskQueue.Mode.APPEND, TaskQueue.Mode.parse(null));
        assertEquals(TaskQueue.Mode.FRONT, TaskQueue.Mode.parse("front"));
        assertEquals(TaskQueue.Mode.REPLACE, TaskQueue.Mode.parse(" Replace "));
        assertThrows(IllegalArgumentException.class, () -> TaskQueue.Mode.parse("sideways"));
    }

    @Test
    void templateAndRetry() {
        QueueEntry t = QueueEntry.fromTemplate(Json.obj("type", "goto", "args", Json.obj("x", 1, "z", 2),
                "timeoutSec", 30, "label", "L"), "panel");
        assertEquals("goto", t.type());
        assertEquals(30, t.timeoutSec());
        QueueEntry r = t.retry();
        assertEquals(1, r.attempts());
        assertTrue(!r.id().equals(t.id()));
        assertEquals(t.args(), r.args());
        assertThrows(IllegalArgumentException.class, () -> QueueEntry.fromTemplate(new JsonObject(), "panel"));
    }
}
