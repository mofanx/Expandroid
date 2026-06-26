using Android.App;
using Android.Content.PM;
using System.Diagnostics;
using Microsoft.Maui;

namespace EspansoGo;

[Activity(Theme = "@style/Maui.SplashTheme", MainLauncher = true, ConfigurationChanges = ConfigChanges.ScreenSize | ConfigChanges.Orientation | ConfigChanges.UiMode | ConfigChanges.ScreenLayout | ConfigChanges.SmallestScreenSize | ConfigChanges.Density)]
public class MainActivity : MauiAppCompatActivity
{
    public override void OnRequestPermissionsResult(int requestCode, string[] permissions, Android.Content.PM.Permission[] grantResults)
    {
        base.OnRequestPermissionsResult(requestCode, permissions, grantResults);
        // Handle Shizuku permission request result (requestCode = 10001)
        if (requestCode == 10001)
        {
            // Permission result will be checked by IsShizukuAuthorized() on next check
            // No need to handle here as the UI will re-check status
        }
    }
}
