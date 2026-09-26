package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.Messages

fun testArchive(): ArchiveService = ArchiveService(InMemorySessionIndex(), InMemorySegmentStore())

fun testMessages(): Messages = Messages(InMemoryMessageStore())
