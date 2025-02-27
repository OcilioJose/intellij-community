// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.changes.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.committed.CommittedChangesTreeBrowser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


public class RevertChangeListAction extends RevertCommittedStuffAbstractAction {
  public RevertChangeListAction() {
    super(true);
  }

  @Override
  protected Change @Nullable [] getChanges(@NotNull AnActionEvent e, boolean isFromUpdate) {
    if (isFromUpdate) {
      return e.getData(VcsDataKeys.CHANGES);
    }
    else {
      CommittedChangesTreeBrowser treeBrowser = e.getData(CommittedChangesTreeBrowser.COMMITTED_CHANGES_TREE_DATA_KEY);
      if (treeBrowser == null) return null;
      return treeBrowser.collectChangesWithMovedChildren();
    }
  }
}
