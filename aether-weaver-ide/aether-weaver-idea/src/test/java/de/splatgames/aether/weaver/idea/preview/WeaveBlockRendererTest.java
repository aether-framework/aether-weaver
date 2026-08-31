package de.splatgames.aether.weaver.idea.preview;

import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

public class WeaveBlockRendererTest extends BasePlatformTestCase {

    private static final int SIZE = 400;

    public void testPaintingABlockDrawsSomething() {
        final BufferedImage image = paint(block("Audit.onCharge()  @HEAD", "System.out.println(\"hi\");"));

        assertTrue("the block must actually be drawn; a renderer that returned early would pass "
                        + "every other assertion in this package", painted(image));
    }

    public void testAnEmptyBlockStillPaints() {
        final BufferedImage image =
                paint(new WeaveBlock(0, "empty", List.of(new WeaveBlock.Section(
                        WeaveBlock.Kind.INJECT, "Audit.onCharge()  @HEAD", "runs on entry", List.of()))));

        assertTrue(painted(image));
    }

    public void testAFragmentWithoutAColourKeyPaints() {
        final BufferedImage image = paint(new WeaveBlock(0, "blank", List.of(new WeaveBlock.Section(
                WeaveBlock.Kind.INJECT, "x  @RETURN", "runs before returning",
                List.of(List.of(new WeaveBlock.Fragment("", null),
                        new WeaveBlock.Fragment("value", null)))))));

        assertTrue(painted(image));
    }

    public void testTheBackgroundIsATintAndNotTheDiffColour() {
        myFixture.configureByText("Target.java", "class Target { }");
        final var scheme = myFixture.getEditor().getColorsScheme();
        final java.awt.Color editorBackground = scheme.getDefaultBackground();
        final java.awt.Color diff = scheme.getColor(
                com.intellij.openapi.editor.colors.EditorColors.ADDED_LINES_COLOR);
        if (diff == null) {
            return;
        }

        final BufferedImage image = paint(block("x  @HEAD", "code();"));
        // Sampled to the right of the accent bar, which deliberately keeps the undiluted colour.
        final java.awt.Color painted = new java.awt.Color(image.getRGB(SIZE / 2, 4), true);

        assertTrue("the fill must be nearer the editor's own background than the diff colour, or "
                        + "the code drawn on top cannot be read: painted=" + painted
                        + " editor=" + editorBackground + " diff=" + diff,
                distance(painted, editorBackground) < distance(painted, diff));
    }

    private static int distance(final java.awt.Color first, final java.awt.Color second) {
        return Math.abs(first.getRed() - second.getRed())
                + Math.abs(first.getGreen() - second.getGreen())
                + Math.abs(first.getBlue() - second.getBlue());
    }

    public void testCollapsedIsAStripAndNotHidden() {
        final WeaveBlock block = block("Audit.onCharge()  @HEAD", "System.out.println(\"hi\");");
        myFixture.configureByText("Target.java", "class Target { }");
        final WeaveBlockRenderer renderer = new WeaveBlockRenderer(block);
        final Inlay<?> inlay = myFixture.getEditor().getInlayModel()
                .addBlockElement(0, true, true, 0, renderer);
        assertNotNull(inlay);

        final int expanded;
        final int collapsed;
        final WeaveInlaySettings settings = WeaveInlaySettings.getInstance();
        try {
            settings.setCollapsed(false);
            expanded = renderer.calcHeightInPixels(inlay);
            settings.setCollapsed(true);
            collapsed = renderer.calcHeightInPixels(inlay);
        } finally {
            settings.setCollapsed(false);
            inlay.dispose();
        }

        assertTrue("collapsed must give the vertical space back: expanded=" + expanded
                + " collapsed=" + collapsed, collapsed < expanded);
        assertTrue("but it must still occupy a line of its own, or the mark disappears with it",
                collapsed > 0);
    }

    public void testCollapsedStillPaintsTheMark() {
        final WeaveInlaySettings settings = WeaveInlaySettings.getInstance();
        try {
            settings.setCollapsed(true);
            final BufferedImage image = paint(block("Audit.onCharge()  @HEAD", "code();"));

            assertTrue("a collapsed block that painted nothing would hide that the method is "
                    + "woven, which is exactly what must survive collapsing", painted(image));
        } finally {
            settings.setCollapsed(false);
        }
    }

    public void testOneBlockCollapsesWithoutTheOthers() {
        final WeaveBlock mine = block("Audit.a()  @HEAD", "a();");
        final WeaveBlock other = block("Audit.b()  @HEAD", "b();");
        myFixture.configureByText("Target.java", "class Target { }");

        final WeaveBlockRenderer first = new WeaveBlockRenderer(mine);
        final WeaveBlockRenderer second = new WeaveBlockRenderer(other);
        final Inlay<?> one = myFixture.getEditor().getInlayModel()
                .addBlockElement(0, true, true, 0, first);
        final Inlay<?> two = myFixture.getEditor().getInlayModel()
                .addBlockElement(0, true, true, 1, second);

        final WeaveCollapsedBlocks collapsed = WeaveCollapsedBlocks.getInstance();
        try {
            final int before = second.calcHeightInPixels(two);
            assertTrue("collapsing must be per block", collapsed.toggle(mine.id()));

            assertTrue("the one that was clicked collapses",
                    first.calcHeightInPixels(one) < before);
            assertEquals("and the one beside it does not — collapsing a block is a statement about "
                            + "that block, not a mode",
                    before, second.calcHeightInPixels(two));
        } finally {
            collapsed.toggle(mine.id());
            one.dispose();
            two.dispose();
        }
    }

    public void testTogglingTwiceRestoresTheBlock() {
        final WeaveCollapsedBlocks collapsed = WeaveCollapsedBlocks.getInstance();
        assertTrue("first toggle collapses", collapsed.toggle("some-id"));
        assertTrue(collapsed.isCollapsed("some-id"));
        assertFalse("second toggle expands", collapsed.toggle("some-id"));
        assertFalse(collapsed.isCollapsed("some-id"));
    }

    public void testTheGutterControlShowsWhichWayItGoes() {
        final WeaveBlock block = block("Audit.onCharge()  @HEAD", "System.out.println(\"hi\");");
        myFixture.configureByText("Target.java", "class Target { }");
        final WeaveBlockRenderer renderer = new WeaveBlockRenderer(block);
        final Inlay<?> inlay = myFixture.getEditor().getInlayModel()
                .addBlockElement(0, true, true, 0, renderer);
        assertNotNull(inlay);

        final WeaveCollapsedBlocks collapsed = WeaveCollapsedBlocks.getInstance();
        try {
            final GutterIconRenderer open = renderer.calcGutterIconRenderer(inlay);
            collapsed.toggle(block.id());
            final GutterIconRenderer folded = renderer.calcGutterIconRenderer(inlay);

            assertNotSame("the chevron must turn", open.getIcon(), folded.getIcon());
            assertFalse("and the renderers must differ, or the platform keeps the old icon",
                    open.equals(folded));
            assertFalse("the tooltip says what a click will do",
                    open.getTooltipText().equals(folded.getTooltipText()));
        } finally {
            collapsed.toggle(block.id());
            inlay.dispose();
        }
    }

    public void testTheGutterControlTogglesItsOwnBlock() {
        final WeaveBlock block = block("Audit.onCharge()  @HEAD", "code();");
        myFixture.configureByText("Target.java", "class Target { }");
        final WeaveBlockRenderer renderer = new WeaveBlockRenderer(block);
        final Inlay<?> inlay = myFixture.getEditor().getInlayModel()
                .addBlockElement(0, true, true, 0, renderer);
        assertNotNull(inlay);

        try {
            final int expanded = renderer.calcHeightInPixels(inlay);
            renderer.calcGutterIconRenderer(inlay).getClickAction()
                    .actionPerformed(com.intellij.testFramework.TestActionEvent.createTestEvent());

            assertTrue("clicking the control must actually collapse the block",
                    renderer.calcHeightInPixels(inlay) < expanded);
        } finally {
            WeaveCollapsedBlocks.getInstance().toggle(block.id());
            inlay.dispose();
        }
    }

    public void testACollapsedStripLeavesRoomForItsChevron() {
        final WeaveBlock block = block("Audit.onCharge()  @HEAD", "code();");
        myFixture.configureByText("Target.java", "class Target { }");
        final WeaveBlockRenderer renderer = new WeaveBlockRenderer(block);
        final Inlay<?> inlay = myFixture.getEditor().getInlayModel()
                .addBlockElement(0, true, true, 0, renderer);
        assertNotNull(inlay);

        final WeaveCollapsedBlocks collapsed = WeaveCollapsedBlocks.getInstance();
        try {
            collapsed.toggle(block.id());
            final int height = renderer.calcHeightInPixels(inlay);
            final int icon = renderer.calcGutterIconRenderer(inlay).getIcon().getIconHeight();

            assertTrue("the strip is " + height + "px and its chevron is " + icon + "px; a control "
                            + "that does not fit is a control that is not drawn", height >= icon);
        } finally {
            collapsed.toggle(block.id());
            inlay.dispose();
        }
    }

    public void testARedirectIsPaintedDifferentlyFromAnInjection() {
        final BufferedImage injected = paint(new WeaveBlock(0, "inject",
                List.of(new WeaveBlock.Section(WeaveBlock.Kind.INJECT, "a  @HEAD", "runs on entry",
                        List.of(List.of(new WeaveBlock.Fragment("code();", null)))))));
        final BufferedImage redirected = paint(new WeaveBlock(0, "redirect",
                List.of(new WeaveBlock.Section(WeaveBlock.Kind.REDIRECT, "a  @INVOKE  replaces", "replaces the call",
                        List.of(List.of(new WeaveBlock.Fragment("code();", null)))))));

        assertFalse("the bar's colour is what says which of the two happened here",
                injected.getRGB(1, 1) == redirected.getRGB(1, 1));
    }

    public void testTheHoverExplainsWhatTheTagMeans() {
        final WeaveBlock block = new WeaveBlock(0, "id",
                List.of(new WeaveBlock.Section(WeaveBlock.Kind.INJECT, "Audit.onCharge()  @HEAD",
                        "Audit.onCharge() runs on entry",
                        List.of(List.of(new WeaveBlock.Fragment("code();", null))))));
        myFixture.configureByText("Target.java", "class Target { }");
        final WeaveBlockRenderer renderer = new WeaveBlockRenderer(block);
        final Inlay<?> inlay = myFixture.getEditor().getInlayModel()
                .addBlockElement(0, true, true, 0, renderer);
        assertNotNull(inlay);

        try {
            final String tooltip = renderer.calcGutterIconRenderer(inlay).getTooltipText();

            assertTrue("the sentence belongs on the hover, where it costs nobody anything: "
                    + tooltip, tooltip.contains("runs on entry"));
            assertTrue("and it still says what a click does: " + tooltip,
                    tooltip.contains("collapse"));
        } finally {
            inlay.dispose();
        }
    }

    private static WeaveBlock block(final String header, final String line) {
        return new WeaveBlock(0, header, List.of(new WeaveBlock.Section(WeaveBlock.Kind.INJECT, header, "runs on entry",
                List.of(List.of(new WeaveBlock.Fragment(line, null))))));
    }

    private BufferedImage paint(final WeaveBlock block) {
        myFixture.configureByText("Target.java", """
                package fixture;

                public class Target {
                    public String charge() {
                        return "x";
                    }
                }
                """);

        final WeaveBlockRenderer renderer = new WeaveBlockRenderer(block);
        final Inlay<?> inlay = myFixture.getEditor().getInlayModel()
                .addBlockElement(block.offset(), true, true, 0, renderer);
        assertNotNull("the platform must accept the inlay, or nothing below means anything", inlay);

        final BufferedImage image =
                new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        try {
            renderer.calcWidthInPixels(inlay);
            renderer.calcHeightInPixels(inlay);
            renderer.paint(inlay, graphics, new Rectangle2D.Double(0, 0, SIZE, SIZE),
                    new TextAttributes());
        } finally {
            graphics.dispose();
            inlay.dispose();
        }
        return image;
    }

    private static boolean painted(final BufferedImage image) {
        for (int x = 0; x < SIZE; x += 4) {
            for (int y = 0; y < SIZE; y += 4) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
