using System.Globalization;

namespace Expandroid.Services;

public interface ILocalizationService
{
    event Action? OnLanguageChanged;
    CultureInfo CurrentCulture { get; }
    void SetCulture(string cultureCode);
    string GetString(string key);
    List<LanguageInfo> GetAvailableLanguages();
    void SaveLanguagePreference(string cultureCode);
    string? LoadLanguagePreference();
}

public class LanguageInfo
{
    public string Code { get; set; } = string.Empty;
    public string Name { get; set; } = string.Empty;
    public string NativeName { get; set; } = string.Empty;
}
