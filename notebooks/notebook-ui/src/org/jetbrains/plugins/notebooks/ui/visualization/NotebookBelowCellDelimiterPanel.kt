package org.jetbrains.plugins.notebooks.ui.visualization

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.Nls
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import java.time.ZonedDateTime

class NotebookBelowCellDelimiterPanel(
  val editor: EditorImpl,
  private val isExecutable: Boolean,
  private val cellTags: List<String>,
  val cellNum: Int,
  isRenderedMarkdown: Boolean,
) : JPanel(BorderLayout()) {
  private val notebookAppearance = editor.notebookAppearance
  private val plusTagButtonSize = JBUI.scale(18)
  private val tagsSpacing = JBUI.scale(6)
  private val delimiterHeight = when (editor.editorKind.isDiff()) {
    true -> getJupyterCellSpacing(editor) / 2
    false -> editor.notebookAppearance.cellBorderHeight / 4
  }
  private var executionLabel: JLabel? = null

  private val updateAlarm = Alarm()  // PY-72226
  private var elapsedStartTime: ZonedDateTime? = null
  private val updateElapsedTimeDelay = 100

  init {
    updateBackgroundColor()
    border = BorderFactory.createEmptyBorder(delimiterHeight, 0, delimiterHeight, 0)

    val addingTagsRow = (cellTags.isNotEmpty() && !isRenderedMarkdown && Registry.`is`("jupyter.cell.metadata.tags", false))

    if (addingTagsRow) add(createTagsRow(), BorderLayout.EAST)  // PY-72712
  }

  private fun createExecutionLabel(): JLabel {
    return JLabel().apply {
      font = EditorUtil.getEditorFont()
      foreground = UIUtil.getLabelInfoForeground()
    }
  }

  @NlsSafe
  private fun getExecutionLabelText(executionCount: Int?, durationText: String?): String {
    val executionCountText = executionCount?.let { if (it > 0) "[$it]" else "" } ?: ""
    val durationLabelText = durationText ?: ""
    val labelText = "$executionCountText $durationLabelText"
    return labelText
  }

  @Suppress("HardCodedStringLiteral")
  private fun createTagsRow(): Box {
    val tagsRow = Box.createHorizontalBox()
    val plusActionToolbar = createAddTagButton()
    tagsRow.add(plusActionToolbar)
    tagsRow.add(Box.createHorizontalStrut(tagsSpacing))

    cellTags.forEach { tag ->
      val tagLabel = NotebookCellTagLabel(tag, cellNum)
      tagsRow.add(tagLabel)
      tagsRow.add(Box.createHorizontalStrut(tagsSpacing))
    }
    return tagsRow
  }

  private fun createAddTagButton(): JButton? {
    // todo: refactor
    // ideally, a toolbar with a single action and targetComponent this should've done that
    // however, the toolbar max height must be not greater than 18, which seemed to be untrivial
    val action = ActionManager.getInstance().getAction("JupyterCellAddTagInlayAction") ?: return null
    val originalIcon = AllIcons.Expui.General.Add
    val transparentIcon = IconLoader.getTransparentIcon(originalIcon)

    return JButton().apply {
      icon = transparentIcon
      preferredSize = Dimension(plusTagButtonSize, plusTagButtonSize)
      isContentAreaFilled = false
      isFocusPainted = false
      isBorderPainted = false
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

      addMouseListener(createAddTagButtonHoverListener(originalIcon, transparentIcon))
      addActionListener(createAddTagButtonActionListener(action))
    }
  }

  private fun createAddTagButtonHoverListener(originalIcon: Icon, transparentIcon: Icon): MouseAdapter {
    return object : MouseAdapter() {
      override fun mouseEntered(e: MouseEvent) {
        (e.source as JButton).icon = originalIcon
      }

      override fun mouseExited(e: MouseEvent) {
        (e.source as JButton).icon = transparentIcon
      }
    }
  }

  private fun createAddTagButtonActionListener(action: AnAction): ActionListener {
    return ActionListener {
      val dataContext = DataContext { dataId ->
        when (dataId) {
          CommonDataKeys.EDITOR.name -> editor
          CommonDataKeys.PROJECT.name -> editor.project
          PlatformCoreDataKeys.CONTEXT_COMPONENT.name -> this@NotebookBelowCellDelimiterPanel
          else -> null
        }
      }
      val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.EDITOR_INLAY, dataContext)
      action.actionPerformed(event)
    }
  }

  private fun updateBackgroundColor() {
    background = when (isExecutable) {
      true -> notebookAppearance.getCodeCellBackground(editor.colorsScheme) ?: editor.colorsScheme.defaultBackground
      false -> editor.colorsScheme.defaultBackground
    }
  }

  private fun isExecutionCountDefined(executionCount: Int?): Boolean = executionCount?.let { it > 0 } ?: false

  @Suppress("USELESS_ELVIS")
  override fun updateUI() {
    // This method is called within constructor of JPanel, at this time state is not yet initialized, reference is null.
    editor ?: return
    updateBackgroundColor()
    super.updateUI()
  }

  fun updateExecutionStatus(@Nls tooltipText: String?, executionCount: Int?, statusIcon: Icon?, @Nls executionDurationText: String?) {
    val showStatus = isExecutionCountDefined(executionCount) || (tooltipText != null && statusIcon != AllIcons.Expui.General.GreenCheckmark)
    if (showStatus) {
      getOrCreateExecutionLabel().apply {
        text = getExecutionLabelText(executionCount, executionDurationText)
        icon = statusIcon
        this.toolTipText = tooltipText
      }
    } else {
      executionLabel?.let { remove(it) }
      executionLabel = null
    }
  }

  private fun updateElapsedTime(@Nls elapsedText: String) = getOrCreateExecutionLabel().apply { text = elapsedText }

  fun startElapsedTimeUpdate(startTime: ZonedDateTime?, diffFormatter: (ZonedDateTime, ZonedDateTime) -> String) {
    // todo: do something about this formatter (circular dependencies issues)
    startTime ?: return
    elapsedStartTime = startTime
    scheduleElapsedTimeUpdate(diffFormatter)
  }

  fun stopElapsedTimeUpdate() = updateAlarm.cancelAllRequests()

  private fun scheduleElapsedTimeUpdate(diffFormatter: (ZonedDateTime, ZonedDateTime) -> String) {
    updateAlarm.addRequest({
      elapsedStartTime?.let { startTime ->
        @NlsSafe val elapsedLabel = diffFormatter(startTime, ZonedDateTime.now())
        updateElapsedTime(elapsedLabel)
        updateAlarm.addRequest({ scheduleElapsedTimeUpdate(diffFormatter) }, updateElapsedTimeDelay)
       }
     }, updateElapsedTimeDelay)
  }

  private fun getOrCreateExecutionLabel(): JLabel {
    return executionLabel ?: createExecutionLabel().also {
      add(it, BorderLayout.WEST)
      executionLabel = it
    }
  }
}
