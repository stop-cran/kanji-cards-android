package io.github.stopcran.kanji.data

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.app.LocaleManager
import io.github.stopcran.kanji.Defaults
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.content.RepoSource
import java.util.Locale

enum class AppLanguage(val storedValue: String, val tag: String?) {
    System("system", null),
    English("en", "en"),
    Russian("ru", "ru");

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.storedValue == value } ?: English
        fun forBaseLanguage(language: String) = if (language == "ru") Russian else English
    }
}

object LanguageSources {
    val russianPilot = RepoSource("stop-cran", "learning-japanese-ru", "ru-pilot-a")

    fun default(language: AppLanguage): RepoSource =
        defaultForBase(AppLocale.resolvedLanguage(language))

    fun defaultForBase(language: String): RepoSource = when (language) {
        "ru" -> RepoSource("stop-cran", "learning-japanese-ru", "main")
        else -> RepoSource.parse(Defaults.CONTENT_REPO_URL, Defaults.CONTENT_BRANCH) ?: error("Invalid built-in English source")
    }

    fun isDefault(source: RepoSource) =
        (source.id == defaultForBase("en").id && source.branch == defaultForBase("en").branch) ||
            (source.id == defaultForBase("ru").id && source.branch == defaultForBase("ru").branch)
}

object AppLocale {
    suspend fun apply(context: Context, language: AppLanguage) {
        val app = context.applicationContext as KanjiApp
        val sourceToSync = app.contentSync.selectAppLanguage(app.settings, language) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java).applicationLocales =
                    language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
            } else {
                (context as? Activity)?.recreate()
            }
        }
        sourceToSync?.let {
            app.syncCoordinator.request(it, SyncKind.Ordinary)
        }
    }

    fun initialize(context: Context, language: AppLanguage, force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java)
        if (force || (manager.applicationLocales.isEmpty && language != AppLanguage.System)) {
            manager.applicationLocales = language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        }
    }

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val language = (context.applicationContext as? KanjiApp)?.settings?.appLanguage?.value
            ?: AppLanguage.parse(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("appLanguage", null))
        val tag = language.tag ?: return context
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        }
        return context.createConfigurationContext(configuration)
    }

    fun systemLanguage(): String = if (LocaleList.getDefault().get(0)?.language == "ru") "ru" else "en"

    fun resolvedLanguage(choice: AppLanguage): String =
        choice.tag?.let { if (it == "ru") "ru" else "en" } ?: systemLanguage()

    fun choiceFromPlatform(context: Context): AppLanguage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val tags = context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        if (tags.isBlank()) return AppLanguage.System
        return when (Locale.forLanguageTag(tags.substringBefore(',')).language) {
            "ru" -> AppLanguage.Russian
            "en" -> AppLanguage.English
            else -> AppLanguage.System
        }
    }
}
