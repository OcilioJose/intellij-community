// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.block.util

import com.intellij.terminal.completion.spec.ShellCommandExecutor
import com.intellij.terminal.completion.spec.ShellCommandResult

internal class TestGeneratorCommandsRunner(
  private val mockCommandResult: suspend (command: String) -> ShellCommandResult
) : ShellCommandExecutor {
  override suspend fun runShellCommand(command: String): ShellCommandResult {
    return mockCommandResult(command)
  }

  companion object {
    val DUMMY: ShellCommandExecutor = TestGeneratorCommandsRunner {
      ShellCommandResult.create("", 0)
    }
  }
}
