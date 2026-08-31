package de.splatgames.aether.weaver.idea.bytecode;

import com.intellij.execution.filters.LineNumbersMapping;
import junit.framework.TestCase;

public class EditorLinesTest extends TestCase {

    private static final LineNumbersMapping MAPPING =
            new LineNumbersMapping.ArrayBasedMapping(new int[]{12, 3, 40, 9});

    public void testAMappedLineIsTranslated() {
        assertEquals("the block belongs where the decompiler put that code, not on line 12",
                3, EditorLines.translate(MAPPING, 12));
        assertEquals(9, EditorLines.translate(MAPPING, 40));
    }

    public void testAnUnmappedLineIsRefused() {
        assertEquals("ArrayBasedMapping answers -1 here, and -1 must not reach a document",
                0, EditorLines.translate(MAPPING, 13));
    }

    public void testNoMappingPlacesNothing() {
        assertEquals(0, EditorLines.translate(null, 12));
    }

    public void testAnAbsentLineStaysAbsent() {
        assertEquals("CompiledLines answers 0 for a site no LineNumber precedes",
                0, EditorLines.translate(MAPPING, 0));
    }
}
