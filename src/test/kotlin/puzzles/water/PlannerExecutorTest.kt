package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.WaterPlannerExecutor
import com.laybalt.features.impl.dungeon.solvers.puzzles.WaterPlannerExecutor.Kind
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The planner thread: per-kind staleness, session ids, checks that never pile up. */
class PlannerExecutorTest {
    private val delivered = Collections.synchronizedList(ArrayList<String>())

    @BeforeTest
    fun setUp() {
        WaterPlannerExecutor.deliver = { it.run() }
    }

    /** Waits until everything submitted so far has run (the executor is one thread, so a marker job is enough). */
    private fun drain(session: Long) {
        val done = CountDownLatch(1)
        WaterPlannerExecutor.submit(Kind.CALIBRATION, session, { }) { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a check does not make a running plan stale`() {
        val session = WaterPlannerExecutor.newSession()
        val gate = CountDownLatch(1)
        WaterPlannerExecutor.submit(Kind.PLAN, session, { gate.await(5, TimeUnit.SECONDS); "plan" }) { delivered += it }
        WaterPlannerExecutor.submit(Kind.CHECK, session, { "check" }) { delivered += it }
        gate.countDown()
        drain(session)
        assertEquals(listOf("plan", "check"), delivered.toList())
    }

    @Test
    fun `a newer plan makes the older one stale`() {
        val session = WaterPlannerExecutor.newSession()
        val gate = CountDownLatch(1)
        WaterPlannerExecutor.submit(Kind.PLAN, session, { gate.await(5, TimeUnit.SECONDS); "old" }) { delivered += it }
        WaterPlannerExecutor.submit(Kind.PLAN, session, { "new" }) { delivered += it }
        gate.countDown()
        drain(session)
        assertEquals(listOf("new"), delivered.toList())
    }

    @Test
    fun `results of an old session are dropped`() {
        val old = WaterPlannerExecutor.newSession()
        val gate = CountDownLatch(1)
        WaterPlannerExecutor.submit(Kind.PLAN, old, { gate.await(5, TimeUnit.SECONDS); "old session" }) { delivered += it }
        val current = WaterPlannerExecutor.newSession()
        assertTrue(current > old)
        gate.countDown()
        drain(current)
        assertEquals(emptyList(), delivered.toList())
    }

    @Test
    fun `a check is skipped while another one still waits`() {
        val session = WaterPlannerExecutor.newSession()
        val gate = CountDownLatch(1)
        WaterPlannerExecutor.submit(Kind.PLAN, session, { gate.await(5, TimeUnit.SECONDS); "plan" }) { delivered += it }
        assertTrue(WaterPlannerExecutor.submit(Kind.CHECK, session, { "first check" }) { delivered += it })
        assertFalse(WaterPlannerExecutor.submit(Kind.CHECK, session, { "second check" }) { delivered += it })
        gate.countDown()
        drain(session)
        assertEquals(listOf("plan", "first check"), delivered.toList())
    }
}
