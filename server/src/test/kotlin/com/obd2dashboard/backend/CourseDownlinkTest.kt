package com.obd2dashboard.backend

import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.live.TabletHandle
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Who hears of a course change (M12.6). */
class CourseDownlinkTest {
    private class Tablet : TabletHandle {
        val sent = mutableListOf<String>()
        override fun superseded() {}
        override fun close(code: Short, reason: String) {}
        override fun send(frame: String) { sent += frame }
    }

    @Test
    fun `only tablets still listening hear of a change`() = runTest {
        val downlink = CourseDownlink(InMemoryCourseStore())
        val stays = Tablet()
        val goes = Tablet()
        downlink.listen(stays)
        downlink.listen(goes)
        downlink.leave(goes) // its socket closed
        downlink.changed()
        stays.sent.single() shouldContain "\"t\":\"courses\""
        goes.sent shouldBe emptyList()
    }
}
