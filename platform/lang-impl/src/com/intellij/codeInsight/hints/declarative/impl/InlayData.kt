// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.hints.declarative.impl

import com.intellij.codeInsight.daemon.impl.ZombieInlayHintsProvider
import com.intellij.codeInsight.hints.declarative.*
import com.intellij.codeInsight.hints.declarative.impl.util.TinyTree
import com.intellij.openapi.fileEditor.impl.text.VersionedExternalizer
import com.intellij.openapi.util.NlsContexts
import com.intellij.util.io.DataInputOutputUtil.readINT
import com.intellij.util.io.DataInputOutputUtil.writeINT
import com.intellij.util.io.IOUtil.readUTF
import com.intellij.util.io.IOUtil.writeUTF
import java.io.DataInput
import java.io.DataOutput

data class InlayData(
  val position: InlayPosition,
  @NlsContexts.HintText val tooltip: String?,
  val hintFormat: HintFormat,
  val tree: TinyTree<Any?>,
  val providerId: String,
  val disabled: Boolean,
  val payloads: List<InlayPayload>?,
  val providerClass: Class<*>, // Just for debugging purposes
  val sourceId: String,
) {

  class Externalizer : VersionedExternalizer<InlayData> {
    private val treeExternalizer: PresentationTreeExternalizer = PresentationTreeExternalizer()

    companion object {
      // increment on format changed
      private const val SERDE_VERSION = 6
    }

    override fun serdeVersion(): Int = SERDE_VERSION + treeExternalizer.serdeVersion()

    override fun save(output: DataOutput, inlayData: InlayData) {
      writePosition(output, inlayData.position)
      writeTooltip(output, inlayData.tooltip)
      output.writeUTF(inlayData.hintFormat.colorKind.name)
      output.writeUTF(inlayData.hintFormat.fontSize.name)
      output.writeUTF(inlayData.hintFormat.horizontalMarginPadding.name)
      treeExternalizer.save(output, inlayData.tree)
      writeUTF(output, inlayData.providerId)
      output.writeBoolean(inlayData.disabled)
      writePayloads(output, inlayData.payloads)
      writeSourceId(output, inlayData.sourceId)
    }

    override fun read(input: DataInput): InlayData {
      val position: InlayPosition       = readPosition(input)
      val tooltip: String?              = readTooltip(input)
      val hintColorKind: HintColorKind  = HintColorKind.valueOf(input.readUTF())
      val hintFontSize: HintFontSize    = HintFontSize.valueOf(input.readUTF())
      val hintHorizontalMarginPadding   = HintMarginPadding.valueOf(input.readUTF())
      val tree: TinyTree<Any?>          = treeExternalizer.read(input)
      val providerId: String            = readUTF(input)
      val disabled: Boolean             = input.readBoolean()
      val payloads: List<InlayPayload>? = readPayloads(input)
      val providerClass: Class<*>       = ZombieInlayHintsProvider::class.java
      val sourceId: String              = readSourceId(input)
      return InlayData(position, tooltip, HintFormat(hintColorKind, hintFontSize, hintHorizontalMarginPadding), tree, providerId, disabled, payloads, providerClass, sourceId)
    }

    private fun writePosition(output: DataOutput, position: InlayPosition) {
      when (position) {
        is InlineInlayPosition -> {
          writeINT(output, 0)
          writeINT(output, position.offset)
          output.writeBoolean(position.relatedToPrevious)
          writeINT(output, position.priority)
        }
        is EndOfLinePosition -> {
          writeINT(output, 1)
          writeINT(output, position.line)
        }
      }
    }

    private fun readPosition(input: DataInput): InlayPosition {
      val type = readINT(input)
      if (type == 0) {
        val offset = readINT(input)
        val related = input.readBoolean()
        val priority = readINT(input)
        return InlineInlayPosition(offset, related, priority)
      } else if (type == 1) {
        val line = readINT(input)
        return EndOfLinePosition(line)
      }
      throw IllegalStateException("unknown inlay position type: $type")
    }

    private fun writeTooltip(output: DataOutput, tooltip: String?) {
      if (tooltip == null) {
        output.writeBoolean(false)
      } else {
        output.writeBoolean(true)
        writeUTF(output, tooltip)
      }
    }

    private fun readTooltip(input: DataInput): String? {
      return if (input.readBoolean()) {
        readUTF(input)
      } else {
        null
      }
    }

    private fun writePayloads(output: DataOutput, payloads: List<InlayPayload>?) {
      if (payloads == null) {
        output.writeBoolean(false)
      } else {
        output.writeBoolean(true)
        writeINT(output, payloads.size)
        for (p in payloads) {
          writeInlayPayload(output, p)
        }
      }
    }

    private fun readPayloads(input: DataInput): List<InlayPayload>? {
      if (input.readBoolean()) {
        val payloadCount = readINT(input)
        val payloads = ArrayList<InlayPayload>(payloadCount)
        repeat(payloadCount) {
          val inlayPayload = readInlayPayload(input)
          payloads.add(inlayPayload)
        }
        return payloads
      } else {
        return null
      }
    }

    private fun writeInlayPayload(output: DataOutput, payload: InlayPayload) {
      writeUTF(output, payload.payloadName)
      treeExternalizer.writeInlayActionPayload(output, payload.payload)
    }

    private fun readInlayPayload(input: DataInput): InlayPayload {
      val payloadName = readUTF(input)
      val inlayActionPayload = treeExternalizer.readInlayActionPayload(input)
      return InlayPayload(payloadName, inlayActionPayload)
    }

    private fun writeSourceId(output: DataOutput, sourceId: String) {
      writeUTF(output, sourceId)
    }

    private fun readSourceId(input: DataInput): String {
      return readUTF(input)
    }
  }
}
