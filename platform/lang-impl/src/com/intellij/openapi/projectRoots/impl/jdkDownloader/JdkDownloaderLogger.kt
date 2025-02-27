// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.projectRoots.impl.jdkDownloader

import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.eventLog.events.EventId1
import com.intellij.internal.statistic.eventLog.events.EventId2
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector
import org.jetbrains.jps.model.java.JdkVersionDetector

object JdkDownloaderLogger : CounterUsagesCollector() {
  override fun getGroup(): EventLogGroup = GROUP

  private val GROUP: EventLogGroup = EventLogGroup("jdk.downloader", 3)

  private const val UNKNOWN_VENDOR = "Unknown"
  private val KNOWN_VENDORS = JdkVersionDetector.Variant.entries
                                .mapNotNull { it.displayName }
                                .toList() + UNKNOWN_VENDOR

  private val DOWNLOAD: EventId1<Boolean> = GROUP.registerEvent("download", EventFields.Boolean("success"))

  private val DETECTED_SDK: EventId2<String?, Int> = GROUP.registerEvent("detected",
                                                                         EventFields.String("product", KNOWN_VENDORS),
                                                                         EventFields.Int("version"))
  private val SELECTED_SDK: EventId2<String?, Int> = GROUP.registerEvent("selected",
                                                                         EventFields.String("product", KNOWN_VENDORS),
                                                                         EventFields.Int("version"))

  fun logDownload(success: Boolean) {
    DOWNLOAD.log(success)
  }

  fun logSelected(jdkItem: JdkItem) {
    val vendor = KNOWN_VENDORS.firstOrNull { jdkItem.fullPresentationText.contains(it) } ?: UNKNOWN_VENDOR
    SELECTED_SDK.log(vendor, jdkItem.jdkMajorVersion)
  }

  @JvmStatic
  fun logDetected(jdkInfo: JdkVersionDetector.JdkVersionInfo?) {
    val (name, version) = when {
                            jdkInfo == null -> null
                            jdkInfo.variant.displayName in KNOWN_VENDORS -> jdkInfo.variant.displayName to jdkInfo.version.feature
                            else -> UNKNOWN_VENDOR to jdkInfo.version.feature
                          } ?: return
    DETECTED_SDK.log(name, version)
  }
}