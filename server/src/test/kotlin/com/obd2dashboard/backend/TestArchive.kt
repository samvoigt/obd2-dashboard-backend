package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex

fun testArchive(): ArchiveService = ArchiveService(InMemorySessionIndex(), InMemorySegmentStore())
