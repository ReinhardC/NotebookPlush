package com.notebookplush

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource

/** The grammar and themes live in assets and work entirely offline. */
internal object JsonHighlighting {
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.assets))
        val registry = ThemeRegistry.getInstance()
        for (name in listOf("plush-light", "plush-dark")) {
            val path = "textmate/$name.json"
            context.assets.open(path).use { input ->
                registry.loadTheme(ThemeModel(IThemeSource.fromInputStream(input, path, null), name).apply {
                    isDark = name == "plush-dark"
                })
            }
        }
        registry.setTheme("plush-light")
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        initialized = true
    }

    fun applyTheme(editor: CodeEditor, dark: Boolean) {
        val registry = ThemeRegistry.getInstance()
        registry.setTheme(if (dark) "plush-dark" else "plush-light")
        editor.colorScheme = TextMateColorScheme.create(registry)
        editor.tag = dark
    }
}
