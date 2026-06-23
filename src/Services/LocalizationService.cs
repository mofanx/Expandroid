using System.Globalization;
using System.Resources;

namespace Expandroid.Services;

public class LocalizationService : ILocalizationService
{
    private readonly ResourceManager _resourceManager;
    private CultureInfo _currentCulture;
    private readonly List<LanguageInfo> _availableLanguages;

    public event Action? OnLanguageChanged;

    public CultureInfo CurrentCulture => _currentCulture;

    public LocalizationService()
    {
        _resourceManager = new ResourceManager("Expandroid.Resources.AppResources", typeof(LocalizationService).Assembly);
        _currentCulture = CultureInfo.CurrentCulture;
        
        // 初始化可用语言列表
        _availableLanguages = new List<LanguageInfo>
        {
            new LanguageInfo { Code = "en", Name = "English", NativeName = "English" },
            new LanguageInfo { Code = "zh", Name = "Chinese", NativeName = "中文" }
        };
        
        // 尝试加载保存的语言偏好
        var savedLanguage = LoadLanguagePreference();
        if (!string.IsNullOrEmpty(savedLanguage))
        {
            SetCulture(savedLanguage);
        }
    }

    public void SetCulture(string cultureCode)
    {
        // Map neutral culture "zh" to specific "zh-CN" for proper ResourceManager satellite assembly lookup
        if (cultureCode == "zh")
            cultureCode = "zh-CN";
        var newCulture = new CultureInfo(cultureCode);
        if (_currentCulture.Name != newCulture.Name)
        {
            _currentCulture = newCulture;
            CultureInfo.CurrentCulture = _currentCulture;
            CultureInfo.CurrentUICulture = _currentCulture;
            SaveLanguagePreference(cultureCode);
            OnLanguageChanged?.Invoke();
        }
    }

    public string GetString(string key)
    {
        try
        {
            return _resourceManager.GetString(key, _currentCulture) ?? key;
        }
        catch
        {
            return key;
        }
    }

    public List<LanguageInfo> GetAvailableLanguages()
    {
        return _availableLanguages;
    }

    public void SaveLanguagePreference(string cultureCode)
    {
        Preferences.Set("app_language", cultureCode);
    }

    public string? LoadLanguagePreference()
    {
        return Preferences.Get("app_language", string.Empty);
    }
}
