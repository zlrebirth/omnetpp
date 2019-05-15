/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.scave.actions;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.ISharedImages;
import org.omnetpp.scave.ScavePlugin;
import org.omnetpp.scave.editors.ScaveEditor;
import org.omnetpp.scave.model.commands.CommandStack;

/**
 * Perform Redo action on the ScaveEditor.
 */
public class RedoAction extends AbstractScaveAction {
    public RedoAction() {
        setText("Redo Scave Operation");
        setImageDescriptor(ScavePlugin.getSharedImageDescriptor(ISharedImages.IMG_TOOL_REDO));
    }

    @Override
    protected void doRun(ScaveEditor editor, IStructuredSelection selection) {
        editor.getActiveCommandStack().redo();
    }

    @Override
    protected boolean isApplicable(ScaveEditor editor, IStructuredSelection selection) {
        CommandStack activeCommandStack = editor.getActiveCommandStack();
		return activeCommandStack != null && activeCommandStack.canRedo();
    }
}