package de.mm20.launcher2.config.service

import de.mm20.launcher2.themes.ThemeRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Fork addition (Phase 2, ADR 0003): config convergence services plus the
 * Android surface around them — [ReloadConfigReceiver] and
 * [ConfigStateProvider] are manifest-declared and resolve [ConfigReloader] /
 * [ConfigStore] / [ReloadReportStore] from here ([ConfigIngestProvider] is
 * pure transport and needs nothing); [ConfigWatcher] is created
 * eagerly so it observes the config file for the whole process lifetime.
 */
val configModule = module {
    // Reuse upstream's construction instead of binding a second instance.
    factory { get<ThemeRepository>().transparencies }
    factory<ProfileResolver> { ProfileManagerProfileResolver(get()) }
    factory<ConfigStore> { DefaultConfigStore(get(), get(), get(), get(), get(), get()) }
    single { ReloadReportStore(androidContext()) }
    factory { ConfigReloader(get(), get()) }
    single(createdAtStart = true) { ConfigWatcher(androidContext(), get(), get()).also { it.start() } }
}
