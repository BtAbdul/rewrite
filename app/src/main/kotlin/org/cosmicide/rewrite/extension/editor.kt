/*
 * This file is part of Cosmic IDE.
 * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.
 */

package org.cosmicide.rewrite.extension

import androidx.core.content.res.ResourcesCompat
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import org.cosmicide.rewrite.R
import org.cosmicide.rewrite.editor.completion.CustomCompletionItemAdapter
import org.cosmicide.rewrite.editor.completion.CustomCompletionLayout

/**
 * Sets the font and enables highlighting of the current line for the code editor.
 */
fun CodeEditor.setFont() {
    typefaceText = ResourcesCompat.getFont(context, R.font.noto_sans_mono)
    isHighlightCurrentLine = true
}

fun CodeEditor.setCompletionLayout() {
    getComponent(EditorAutoCompletion::class.java).apply {
        setAdapter(CustomCompletionItemAdapter())
        setLayout(CustomCompletionLayout())
    }
}