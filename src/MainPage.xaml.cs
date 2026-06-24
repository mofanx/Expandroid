using CommunityToolkit.Maui.Behaviors;
using EspansoGo.Services;

namespace EspansoGo;

public partial class MainPage : ContentPage
{
    private readonly IThemeService _themeService;

    public MainPage(IThemeService themeService)
    {
        InitializeComponent();
        _themeService = themeService;
        ApplyThemeColors();
        _themeService.OnThemeChanged += OnThemeChanged;
    }

    private void OnThemeChanged()
    {
        MainThread.BeginInvokeOnMainThread(ApplyThemeColors);
    }

    private void ApplyThemeColors()
    {
        if (_themeService.IsDarkMode)
        {
            BackgroundColor = Color.FromArgb("#0f172a");
            if (Behaviors.FirstOrDefault(b => b is StatusBarBehavior) is StatusBarBehavior sb)
                sb.StatusBarColor = Color.FromArgb("#1e1b4b");
        }
        else
        {
            BackgroundColor = Color.FromArgb("#f8fafc");
            if (Behaviors.FirstOrDefault(b => b is StatusBarBehavior) is StatusBarBehavior sb)
                sb.StatusBarColor = Color.FromArgb("#6366f1");
        }
    }
}
