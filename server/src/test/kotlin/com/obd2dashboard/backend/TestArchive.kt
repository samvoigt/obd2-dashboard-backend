package com.obd2dashboard.backend

import com.obd2dashboard.backend.courses.CourseStore
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.events.InMemoryDriverStore
import com.obd2dashboard.backend.events.InMemoryEventStore
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.Messages

fun testArchive(): ArchiveService = ArchiveService(InMemorySessionIndex(), InMemorySegmentStore())

fun testMessages(): Messages = Messages(InMemoryMessageStore())

/** A fixed crew-cookie key for tests (production's comes from Secret Manager). */
fun testCrewKey(): ByteArray = ByteArray(32) { it.toByte() }

fun testCourses(): CourseStore = InMemoryCourseStore()

fun testEvents(): EventStores = EventStores(InMemoryDriverStore(), InMemoryEventStore())
