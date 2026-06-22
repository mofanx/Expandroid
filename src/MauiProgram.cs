using CommunityToolkit.Maui;
using CommunityToolkit.Maui.Storage;
using Expandroid.Models;
using Expandroid.Services;
using MudBlazor.Services;
using Microsoft.Maui.Storage;

namespace Expandroid;

public static class MauiProgram
{
    public static MauiApp CreateMauiApp()
    {
        MauiAppBuilder builder = MauiApp.CreateBuilder();
        builder
            .UseMauiApp<App>().UseMauiCommunityToolkit()
            .ConfigureFonts(fonts =>
            {
                fonts.AddFont("OpenSans-Regular.ttf", "OpenSansRegular");
            });

        builder.Services.AddMauiBlazorWebView();
        builder.Services.AddMudServices();
#if DEBUG
        builder.Services.AddBlazorWebViewDeveloperTools();
#endif
#if ANDROID
        builder.Services.AddSingleton<ICheckIfActivated, CheckIfActivated>();
        builder.Services.AddSingleton<SafManager>(sp =>
            new SafManager(Android.App.Application.Context));
        builder.Services.AddSingleton<YamlWorkspace>(sp =>
            new YamlWorkspace(sp.GetRequiredService<SafManager>()));
#else
        builder.Services.AddSingleton<YamlWorkspace>();
#endif
        builder.Services.AddSingleton<IDialogService, DialogService>();
        builder.Services.AddSingleton<ILocalizationService, LocalizationService>();
        builder.Services.AddSingleton<IThemeService, ThemeService>();
        builder.Services.AddSingleton(FileSaver.Default);
        builder.Services.AddSingleton(FilePicker.Default);
        builder.Services.AddSingleton<SyncManager>();
        return builder.Build();
    }
}
