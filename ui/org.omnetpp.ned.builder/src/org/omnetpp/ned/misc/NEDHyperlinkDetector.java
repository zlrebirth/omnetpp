package org.omnetpp.ned.misc;

import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.omnetpp.common.editor.text.NedCompletionHelper.NedWordDetector;
import org.omnetpp.ned.model.interfaces.INEDTypeInfo;
import org.omnetpp.ned.resources.NEDResourcesPlugin;

public class NEDHyperlinkDetector implements IHyperlinkDetector {

    public IHyperlink[] detectHyperlinks(ITextViewer textViewer, IRegion region, boolean canShowMultipleHyperlinks) {
        // extract the word under the cursor. go back and forward from the cursor point and detect word
        // boundaries
        String text = textViewer.getDocument().get();
        NedWordDetector nwd = new NedWordDetector();
        int wordStart = region.getOffset();
        // if we do not point into a word, just skip the whole detection
        if (!(nwd.isWordPart(text.charAt(wordStart)) || nwd.isWordStart(text.charAt(wordStart))))
            return null;
        // look for the start of the word
        while (wordStart >= 0 && (nwd.isWordPart(text.charAt(wordStart)) || nwd.isWordStart(text.charAt(wordStart))))
            wordStart--;
        wordStart++;            // go back to the first letter
        int wordEnd = region.getOffset();
        while (wordEnd < text.length() && (nwd.isWordPart(text.charAt(wordEnd)) || nwd.isWordStart(text.charAt(wordEnd))))
            wordEnd++;

        int wordLength = wordEnd-wordStart;
        
        String word = text.substring(wordStart, wordEnd);
        INEDTypeInfo nedComonentUnderCursor = NEDResourcesPlugin.getNEDResources().getComponent(word);
        if (nedComonentUnderCursor != null)
            return new IHyperlink[] {new NEDHyperlink(new Region(wordStart, wordLength),
                                                      nedComonentUnderCursor.getNEDElement())};
        // if not found among component we do not create a hyperlink
        return null;
    }

}
