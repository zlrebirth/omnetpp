package org.omnetpp.scave2.actions;

import java.util.Collection;
import java.util.Iterator;

import org.eclipse.emf.common.command.CompoundCommand;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.edit.command.AddCommand;
import org.eclipse.emf.edit.command.RemoveCommand;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.omnetpp.scave.model.Group;
import org.omnetpp.scave.model.ScaveModelFactory;
import org.omnetpp.scave2.editors.ScaveEditor;

/**
 * Groups the selected objects. The objects must be siblings and
 * form a continuous range.
 */
public class GroupAction extends AbstractScaveAction {
	public GroupAction() {
		setText("Group");
		setToolTipText("Surround selected items with a group item");
	}

	@Override
	protected void doRun(ScaveEditor editor, IStructuredSelection selection) {
		RangeSelection range = asRangeSelection(selection);
		if (range != null) {
			Collection elements = selection.toList();
			Group group = ScaveModelFactory.eINSTANCE.createGroup();
			CompoundCommand command = new CompoundCommand("Group");
			command.append(new RemoveCommand(editor.getEditingDomain(), range.elist, elements));
			command.append(new AddCommand(editor.getEditingDomain(), range.elist, group, range.fromIndex));
			command.append(new AddCommand(editor.getEditingDomain(), group.getItems(), elements));
			editor.executeCommand(command);
		}
	}

	@Override
	protected boolean isApplicable(ScaveEditor editor, IStructuredSelection selection) {
		return asRangeSelection(selection) != null;
	}
	
	static class RangeSelection {
		public EList elist;
		public int fromIndex;
		public int toIndex;
	}
	
	private static RangeSelection asRangeSelection(IStructuredSelection selection) {
		if (selection == null || selection.size() == 0 || !containsEObjectsOnly(selection))
			return null;
		
		RangeSelection range = new RangeSelection();
		Iterator elements = selection.iterator();
		while (elements.hasNext()) {
			EObject element = (EObject)elements.next();
			Object elist = element.eContainer() != null ? element.eContainer().eGet(element.eContainingFeature()) : null;
			if (!(elist instanceof EList))
				return null;
			
			if (range.elist == null) { // first iteration
				range.elist = (EList)elist;
				range.fromIndex = range.toIndex = range.elist.indexOf(element);
			}
			else if (range.elist == elist) { // sibling
				int index = range.elist.indexOf(element);
				range.fromIndex = Math.min(range.fromIndex, index);
				range.toIndex = Math.max(range.toIndex, index);
			}
			else // not sibling
				return null;
		}
		
		if (selection.size() != range.toIndex - range.fromIndex + 1)
			return null;
		
		return range;
	}
}
