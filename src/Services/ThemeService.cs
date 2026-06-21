using Microsoft.Maui.Storage;

namespace Expandroid.Services;

public class ThemeService : IThemeService
{
    private AppTheme _currentTheme;
    private bool _isDarkMode;

    public AppTheme CurrentTheme => _currentTheme;
    public bool IsDarkMode => _isDarkMode;
    public event Action? OnThemeChanged;

    public ThemeService()
    {
        _currentTheme = LoadThemePreference();
        ApplyTheme();
    }

    public void SetTheme(AppTheme theme)
    {
        if (_currentTheme == theme) return;
        _currentTheme = theme;
        SaveThemePreference(theme);
        ApplyTheme();
        OnThemeChanged?.Invoke();
    }

    public void ApplyTheme()
    {
        _isDarkMode = _currentTheme switch
        {
            AppTheme.Dark => true,
            AppTheme.Light => false,
            AppTheme.Auto => Application.Current?.RequestedTheme == Microsoft.Maui.ApplicationModel.AppTheme.Dark,
            _ => true
        };
    }

    private static void SaveThemePreference(AppTheme theme)
    {
        Preferences.Set("app_theme", theme.ToString());
    }

    private static AppTheme LoadThemePreference()
    {
        var saved = Preferences.Get("app_theme", "Auto");
        return Enum.TryParse<AppTheme>(saved, true, out var theme) ? theme : AppTheme.Auto;
    }
}
