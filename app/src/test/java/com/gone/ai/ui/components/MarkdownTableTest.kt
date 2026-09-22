package com.gone.ai.ui.components

import org.junit.Assert.*
import org.junit.Test

class MarkdownTableTest {
    @Test fun labTablePreservesValuesAndUnits() {
        val text = "| Test | Value | Unit |\n| --- | :---: | ---: |\n| Hb | **12.5** | g/dL |\n| Flag | + | % |"
        val table = MarkdownBlocks.parse(text).single() as MarkdownBlock.Table
        assertEquals(listOf("Test", "Value", "Unit"), table.headers)
        assertEquals("+", table.rows[1][1])
        assertEquals("Test\tValue\tUnit\nHb\t12.5\tg/dL\nFlag\t+\t%", MarkdownBlocks.plainText(text))
    }

    @Test fun ordinaryPipeTextIsNotInventedIntoTable() {
        assertTrue(MarkdownBlocks.parse("A | B\nnot a divider").none { it is MarkdownBlock.Table })
    }
}
