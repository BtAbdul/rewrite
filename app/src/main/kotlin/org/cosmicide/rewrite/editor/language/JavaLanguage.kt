/*
 * This file is part of Cosmic IDE.
 * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.
 */

package org.cosmicide.rewrite.editor.language

import android.os.Bundle
import android.util.Log
import com.tyron.javacompletion.JavaCompletions
import com.tyron.javacompletion.completion.CompletionCandidate
import com.tyron.javacompletion.options.JavaCompletionOptionsImpl
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.langs.textmate.IdeLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.cosmicide.completion.java.parser.CompletionProvider
import org.cosmicide.project.Project
import org.cosmicide.rewrite.common.Prefs
import org.cosmicide.rewrite.editor.EditorCompletionItem
import java.io.File
import java.net.URI
import java.util.logging.Level

/**
 * A custom implementation of an IDE language for Java.
 *
 * @property editor A reference to the [CodeEditor] instance for this language.
 * @property project The [Project] instance for this language.
 * @property file The [File] instance for this language.
 */
class JavaLanguage(
    val editor: CodeEditor,
    val project: Project,
    val file: File
) : IdeLanguage(
    grammarRegistry.findGrammar("source.java"),
    grammarRegistry.findLanguageConfiguration("source.java"),
    grammarRegistry,
    themeRegistry
) {
    private lateinit var completionProvider: CompletionProvider

    private val completions by lazy { JavaCompletions() }
    private val path = file.toPath()

    init {
        if (Prefs.experimentalJavaCompletion) {
            CoroutineScope(Dispatchers.IO).launch {
                completionProvider = CompletionProvider()
            }
        }
        val options = JavaCompletionOptionsImpl(
            "${project.binDir.absolutePath}/autocomplete.log",
            Level.ALL,
            emptyList(),
            emptyList()
        )
        completions.initialize(URI("file://" + project.root.absolutePath), options)
        completions.openFile(file.toPath(), editor.text.toString())
    }

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        super.requireAutoComplete(content, position, publisher, extraArguments)

        try {
            val text = editor.text.toString()
            if (Prefs.experimentalJavaCompletion) {
                val items = completionProvider.complete(text, "Main.java", position.index)
                publisher.setComparator(Comparator<CompletionItem> { o1, o2 ->
                    // if the first letter of the label is lowercase, then its most likely a module/package
                    if (o1.label[0].isLowerCase() && o2.label[0].isUpperCase()) {
                        return@Comparator -1
                    } else if (o1.label[0].isUpperCase() && o2.label[0].isLowerCase()) {
                        return@Comparator 1
                    }
                    return@Comparator o1.label.toString().compareTo(o2.label.toString())
                })
                publisher.addItems(items)
                return
            }
            completions.updateFileContent(path, text)
            val result = completions.getCompletions(path, position.line, position.column)
            result.completionCandidates.forEach { candidate ->
                if (candidate.name != "<error>") {
                    val item = EditorCompletionItem(
                        candidate.name,
                        candidate.detail.orElse(candidate.kind.name),
                        result.prefix.length,
                        candidate.name
                    )

                    val kind = when (candidate.kind) {
                        CompletionCandidate.Kind.CLASS -> CompletionItemKind.Class
                        CompletionCandidate.Kind.INTERFACE -> CompletionItemKind.Interface
                        CompletionCandidate.Kind.ENUM -> CompletionItemKind.Enum
                        CompletionCandidate.Kind.METHOD -> CompletionItemKind.Method
                        CompletionCandidate.Kind.FIELD -> CompletionItemKind.Field
                        CompletionCandidate.Kind.VARIABLE -> CompletionItemKind.Variable
                        CompletionCandidate.Kind.PACKAGE -> CompletionItemKind.Module
                        CompletionCandidate.Kind.KEYWORD -> CompletionItemKind.Keyword

                        else -> {
                            CompletionItemKind.Text
                        }
                    }
                    item.kind(kind)
                    publisher.addItem(item)
                }
            }
        } catch (e: Throwable) {
            if (e !is InterruptedException) {
                Log.e(TAG, "Failed to fetch code completions", e)
            }
        }
    }


    companion object {
        private const val TAG = "JavaLanguage"
        private val grammarRegistry = GrammarRegistry.getInstance()
        private val themeRegistry = ThemeRegistry.getInstance()
    }
}