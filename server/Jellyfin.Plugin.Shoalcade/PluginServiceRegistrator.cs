using System;
using System.IO;
using System.Net.Http;
using Jellyfin.Data.Events.Users;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.Scanner;
using Jellyfin.Plugin.Shoalcade.Web;
using MediaBrowser.Common.Net;
using MediaBrowser.Controller;
using MediaBrowser.Controller.Configuration;
using MediaBrowser.Controller.Events;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Plugins;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.IO;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade;

/// <summary>Registers the plugin's services with Jellyfin.</summary>
public class PluginServiceRegistrator : IPluginServiceRegistrator
{
    /// <inheritdoc />
    public void RegisterServices(IServiceCollection serviceCollection, IServerApplicationHost applicationHost)
    {
        serviceCollection.AddSingleton<ShoalcadeServices>();
        serviceCollection.AddSingleton<RomLibrary>();
        serviceCollection.AddSingleton<WebAssets>();
        // The phone app bundled in the plugin folder (D134) and its in-memory QR download tickets.
        serviceCollection.AddSingleton(sp => new PhoneApp(PhoneApp.DefaultFolder(), () => ShoalcadeServices.Config.OfferPhoneApp, sp.GetRequiredService<ILogger<PhoneApp>>()));
        serviceCollection.AddSingleton(_ => new AppTickets(TimeProvider.System));
        serviceCollection.AddSingleton<GamesLibrarySetup>();

        // Jellyfin's download/file routes never serve a ROM item whose file is outside its library (G5 s0.2 M1).
        serviceCollection.Configure<MvcOptions>(o => o.Filters.Add<RomFileGuard>());

        // Games libraries kept off Jellyfin's home page (D109): applied at start-up and every 6 hours (scheduled task), after
        // each library scan, for each new user and library access change, when a games library is made here, when the admin setting changes, and right
        // after someone saves their own Settings → Home screen (Jellyfin web drops My Media exclusions on that save).
        serviceCollection.AddSingleton(sp =>
        {
            var setup = sp.GetRequiredService<GamesLibrarySetup>();
            var hider = new HomePageHider(
                Path.Combine(sp.GetRequiredService<ShoalcadeServices>().DataRoot, "home-page.json"),
                new JellyfinHomeExcludesStore(sp.GetRequiredService<IUserManager>()),
                setup.GamesLibraryIds,
                () => ShoalcadeServices.Config.KeepGamesOffHomePage,
                sp.GetRequiredService<ILogger<HomePageHider>>());
            if (Plugin.Instance is { } plugin)
            {
                plugin.ConfigurationChanged += (_, _) => _ = hider.SyncQuietlyAsync();
            }

            return hider;
        });
        serviceCollection.AddScoped<IEventConsumer<UserCreatedEventArgs>, KeepGamesOffHomePageForUserChanges>();
        serviceCollection.AddScoped<IEventConsumer<UserUpdatedEventArgs>, KeepGamesOffHomePageForUserChanges>();
        serviceCollection.AddTransient<IStartupFilter, HomePageWatchStartupFilter>();

        // Our own library scanner (phase 3, D42): RomResolver, RomInfoProvider and Md5ExternalId are found by Jellyfin
        // itself; they share the JellyEMU guard and the MD5 cache.
        serviceCollection.AddSingleton<ScannerGuard>();

        // Our box art source ticked once per games library (a library made by hand in the dashboard lists its image
        // sources and leaves new ones unticked): at start-up, after each scan and when a games library is made here.
        serviceCollection.AddSingleton(sp => new BoxArtSwitch(
            Path.Combine(sp.GetRequiredService<ShoalcadeServices>().DataRoot, "box-art.json"),
            new JellyfinLibraryImageSettings(
                sp.GetRequiredService<ILibraryManager>(),
                sp.GetRequiredService<IServerConfigurationManager>(),
                sp.GetRequiredService<IProviderManager>(),
                sp.GetRequiredService<IFileSystem>()),
            sp.GetRequiredService<GamesLibrarySetup>().GamesLibraryIds,
            () => sp.GetRequiredService<ScannerGuard>().StandDown,
            sp.GetRequiredService<ILogger<BoxArtSwitch>>()));
        serviceCollection.AddSingleton(sp => new Md5Cache(
            Path.Combine(sp.GetRequiredService<ShoalcadeServices>().DataRoot, "scanner", "md5.json"),
            TimeProvider.System,
            sp.GetRequiredService<ILogger<Md5Cache>>()));

        // Game names + box art (D48): libretro-database lists downloaded into shoalcade/gamedb (used by RomInfoProvider)
        // and libretro-thumbnails pictures (BoxArtProvider, found by Jellyfin itself). One polite HTTP client for both.
        serviceCollection.AddSingleton(sp => new LibretroHttp(
            () => sp.GetRequiredService<IHttpClientFactory>().CreateClient(NamedClient.Default),
            TimeProvider.System));
        serviceCollection.AddSingleton(sp => new GameDatabase(
            Path.Combine(sp.GetRequiredService<ShoalcadeServices>().DataRoot, "gamedb"),
            sp.GetRequiredService<LibretroHttp>(),
            TimeProvider.System,
            sp.GetRequiredService<ILogger<GameDatabase>>()));
        serviceCollection.AddSingleton(sp => new ThumbnailMisses(
            Path.Combine(sp.GetRequiredService<ShoalcadeServices>().DataRoot, "gamedb", "thumbnail-misses.json"),
            TimeProvider.System,
            sp.GetRequiredService<ILogger<ThumbnailMisses>>()));

        // Admin game editor (D122): edit records + covers in shoalcade/edits and shoalcade/covers (never next to the ROMs),
        // "Find a cover" through the same polite libretro client, and a watch that puts an admin's cover back if a scan or
        // "Replace all metadata" replaces it. The name is protected by Jellyfin's own Name lock.
        serviceCollection.AddSingleton(sp => new GameEdits(
            sp.GetRequiredService<ShoalcadeServices>().DataRoot,
            sp.GetRequiredService<ILogger<GameEdits>>()));
        serviceCollection.AddSingleton<GameEditor>();
        serviceCollection.AddSingleton<CoverFinder>();
        serviceCollection.AddHostedService<GameEditWatch>();

        // Shoalcade link + Play/Saves buttons inside Jellyfin's own web client (D67). No other plugin needed.
        serviceCollection.AddTransient<IStartupFilter, JellyfinWebLinksStartupFilter>();
    }
}
