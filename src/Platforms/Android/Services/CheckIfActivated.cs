using Android;
using Android.AccessibilityServices;
using Android.Content;
using Android.Content.PM;
using Android.Provider;
using Android.Views.Accessibility;
using AndroidX.Core.App;
using AndroidX.Core.Content;
using EspansoGo.Models;
using Microsoft.Maui.ApplicationModel;

namespace EspansoGo.Services
{
    internal class CheckIfActivated : ICheckIfActivated
    {
        private const string ShizukuProviderAuthority = "moe.shizuku.privileged.api";
        private const string ShizukuPermission = "moe.shizuku.manager.permission.API_V23";
        private const int ShizukuRequestCode = 10001;

        public bool IsActivated()
        {
            var context = Microsoft.Maui.ApplicationModel.Platform.CurrentActivity.BaseContext;
            AccessibilityManager am = (AccessibilityManager)context.GetSystemService(Context.AccessibilityService);
            IList<AccessibilityServiceInfo> enabledServices = am.GetEnabledAccessibilityServiceList(FeedbackFlags.Generic);

            foreach (AccessibilityServiceInfo enabledService in enabledServices)
            {
                ServiceInfo enabledServiceInfo = enabledService.ResolveInfo.ServiceInfo;
                if (enabledServiceInfo.PackageName.Equals(context.PackageName))
                    return true;
            }

            return false;
        }
        public void OpenSettings()
        {
            var context = Microsoft.Maui.ApplicationModel.Platform.CurrentActivity.BaseContext;
            var componentName = new ComponentName(context.PackageName, context.PackageName + ".ExpanderAccessibilityservice");
            var flattened = componentName.FlattenToString();

            // Try ACCESSIBILITY_DETAIL_SETTINGS (Android 12+) with component name to open service detail page directly
            if (!TryStartActivity(context, "android.settings.ACCESSIBILITY_DETAIL_SETTINGS", flattened))
            {
                // Fallback: open accessibility settings list with component name to highlight our service
                if (!TryStartActivity(context, Settings.ActionAccessibilitySettings, flattened))
                {
                    // Last resort: open accessibility settings list without component name
                    try
                    {
                        var intent = new Intent(Settings.ActionAccessibilitySettings);
                        intent.SetFlags(ActivityFlags.NewTask);
                        context.StartActivity(intent);
                    }
                    catch (Exception e)
                    {
                        System.Diagnostics.Debug.WriteLine($"OpenSettings all attempts failed: {e.Message}");
                    }
                }
            }
        }

        private static bool TryStartActivity(Android.Content.Context context, string action, string componentNameExtra)
        {
            try
            {
                var intent = new Intent(action);
                if (!string.IsNullOrEmpty(componentNameExtra) && Android.OS.Build.VERSION.SdkInt >= Android.OS.BuildVersionCodes.O)
                    intent.PutExtra(Intent.ExtraComponentName, componentNameExtra);
                intent.SetFlags(ActivityFlags.NewTask);
                context.StartActivity(intent);
                return true;
            }
            catch
            {
                return false;
            }
        }
        public bool RequestPermission()
        {
            var activity = Platform.CurrentActivity ?? throw new NullReferenceException("Current activity is null");

            if (ContextCompat.CheckSelfPermission(activity, Manifest.Permission.WriteExternalStorage) == Permission.Granted)
            {
                return true;
            }

            if (ActivityCompat.ShouldShowRequestPermissionRationale(activity, Manifest.Permission.WriteExternalStorage))
            {
                //await Toast.Make("Please grant access to external storage", ToastDuration.Short, 12).Show();
            }

            ActivityCompat.RequestPermissions(activity, new[] { Manifest.Permission.WriteExternalStorage }, 1);

            return false;
        }

        public bool IsShizukuAvailable()
        {
            try
            {
                var context = Microsoft.Maui.ApplicationModel.Platform.CurrentActivity?.BaseContext;
                if (context == null) return false;

                // Check if Shizuku package is installed
                var pm = context.PackageManager;
                var shizukuInfo = pm.GetPackageInfo("moe.shizuku.privileged.api", 0);
                if (shizukuInfo == null) return false;

                // Check if Shizuku binder is alive by querying its ContentProvider
                var uri = Android.Net.Uri.Parse($"content://{ShizukuProviderAuthority}");
                using var cursor = context.ContentResolver.Query(uri, null, null, null, null);
                return cursor != null;
            }
            catch
            {
                return false;
            }
        }

        public bool IsShizukuAuthorized()
        {
            try
            {
                var context = Microsoft.Maui.ApplicationModel.Platform.CurrentActivity?.BaseContext;
                if (context == null) return false;

                return ContextCompat.CheckSelfPermission(context, ShizukuPermission) == Permission.Granted;
            }
            catch
            {
                return false;
            }
        }

        public Task<bool> RequestShizukuAuthorization()
        {
            try
            {
                var activity = Platform.CurrentActivity;
                if (activity == null) return Task.FromResult(false);

                // Use ActivityCompat to request the Shizuku permission
                ActivityCompat.RequestPermissions(activity, new[] { ShizukuPermission }, ShizukuRequestCode);
                return Task.FromResult(true);
            }
            catch (Exception e)
            {
                System.Diagnostics.Debug.WriteLine($"RequestShizukuAuthorization failed: {e.Message}");
                return Task.FromResult(false);
            }
        }

        public async Task<bool> TryEnableAccessibility()
        {
            // TODO: Implement via Shizuku bindUserService to call hidden API
            // setAccessibilityServiceEnabled on AccessibilityManager
            // For now, fallback to opening system settings
            await Task.Delay(100);
            return false;
        }
    }
}
