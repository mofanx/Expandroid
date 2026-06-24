using CommunityToolkit.Mvvm.Messaging;
using EspansoGo.Models;
using EspansoGo.Services;

namespace EspansoGo;

public partial class App : Application
{
    private Window window = null;
    private readonly IThemeService _themeService;

    public App(IThemeService themeService)
    {
        InitializeComponent();
        _themeService = themeService;
        MainPage = new MainPage(_themeService);
    }
    protected override Window CreateWindow(IActivationState activationState)
    {
        if (window is null)
        {
            window = base.CreateWindow(activationState);
        }
        else
        {
            MainPage = new MainPage(_themeService);
        }
        return window;
    }
    protected override void OnResume()
    {
        base.OnResume();
        WeakReferenceMessenger.Default.Send(new AppResumedMessage());
    }
}
