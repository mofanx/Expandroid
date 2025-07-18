using Android.App;
using Android.Content;
using Android.OS;
using CommunityToolkit.Mvvm.Messaging;
using Expandroid.Models;
using System.Text.Json;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

[BroadcastReceiver(Enabled = true, Exported = true)]
[IntentFilter(new[] { "com.dingleinc.texttoolspro.IMPORT_CONFIG" })]
public class ConfigImportReceiver : BroadcastReceiver
{
    // Tasker and MacroDroid package names
    private static readonly string[] AllowedPackages = new[]
    {
        "net.dinglisch.android.taskerm",    // Tasker
        "com.arlosoft.macrodroid"           // MacroDroid
    };
    private void SendMessage(string cmd, Match value)
    {
        WeakReferenceMessenger.Default.Send(new AcServiceMessage((cmd, value)));
    }
    public override void OnReceive(Context context, Intent intent)
    {
        // Only check on Android 4.4+ (API 19+)
        if (Build.VERSION.SdkInt >= BuildVersionCodes.JellyBeanMr1)
        {
            int callingUid = Binder.CallingUid;
            var pm = context.PackageManager;
            var packages = pm.GetPackagesForUid(callingUid);
            if (packages == null || !packages.Any(pkg => AllowedPackages.Contains(pkg)))
                return;
        }

        var configStr = intent.GetStringExtra("config_string");
        if (!string.IsNullOrEmpty(configStr))
        {
            File.WriteAllText(AppSettings.DictPath, configStr);
            var deserializer = new DeserializerBuilder()
                        .WithNamingConvention(UnderscoredNamingConvention.Instance).IgnoreUnmatchedProperties()
                        .Build();
            var localDict = deserializer.Deserialize<DictWrapper>(configStr);
            foreach (var item in localDict.Matches)
            {
                if (item.Vars is not null)
                {
                    bool notSupported = item.Replace is null;
                    foreach (var x in item.Vars)
                    {
                        if (x.Type is not null)
                        {
                            if (!AppSettings.SupportedList.Contains(x.Type))
                            {
                                notSupported = true;
                                break;
                            }
                            else if (x.Type == "date")
                            {
                                try
                                {
                                    x.Params.Format = Utils.GetTheRealFormat(x.Params.Format);
                                }
                                catch (Exception)
                                {
                                    throw new Exception("Please make sure date extension parameter formats are present!");
                                }
                            }
                        }
                    }
                    if (notSupported)
                        continue;
                }
                SendMessage("Add", item);
            }
            if (localDict.Global_vars is not null)
            {
                var str = JsonSerializer.Serialize(localDict.Global_vars);
                File.WriteAllText(AppSettings.GlobalVarsPath, str);
                WeakReferenceMessenger.Default.Send(new AcGlobalsMessage(localDict.Global_vars));
            }
        }
    }
}