using Android.App;
using Android.Content;
using Android.OS;
using CommunityToolkit.Mvvm.Messaging;
using Expandroid.Models;
using System.Text.Json;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

[BroadcastReceiver(Enabled = true, Exported = true, Name = "com.dingleinc.texttoolspro.ConfigImportReceiver")]
[IntentFilter(new[] { "com.dingleinc.texttoolspro.IMPORT_CONFIG" })]
public class ConfigImportReceiver : BroadcastReceiver
{
    //// Tasker and MacroDroid package names
    //private static readonly string[] AllowedPackages = new[]
    //{
    //    "net.dinglisch.android.taskerm",    // Tasker
    //    "com.arlosoft.macrodroid",           // MacroDroid
    //    "com.dingleinc.texttoolspro"
    //};
    private void SendMessage(string cmd, Match value)
    {
        WeakReferenceMessenger.Default.Send(new AcServiceMessage((cmd, value)));
    }
    public override void OnReceive(Context? context, Intent? intent)
    {
        if (intent is null) return;
        // Only check on Android 4.4+ (API 19+)
        if (Build.VERSION.SdkInt >= BuildVersionCodes.JellyBeanMr1)
        {
            //int callingUid = Binder.CallingUid;
            //var pm = context.PackageManager;
            //var packages = pm.GetPackagesForUid(callingUid);
            //if (packages == null || !packages.Any(pkg => AllowedPackages.Contains(pkg)))
            //    return;
            var configStr = intent.GetStringExtra("config_string");
            if (!string.IsNullOrEmpty(configStr))
            {
                try
                {
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
                                        Android.Util.Log.Warn("ConfigImport", $"Skipped match '{item.Trigger}': unsupported var type '{x.Type}'");
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
                                            throw new Exception("Date extension parameter formats error");
                                        }
                                    }
                                }
                            }
                            if (notSupported)
                            {
                                if (item.Replace is null)
                                    Android.Util.Log.Warn("ConfigImport", $"Skipped match '{item.Trigger}': missing replace field");
                                continue;
                            }
                        }
                    }
                    if (localDict.Global_vars is not null)
                    {
                        var str = JsonSerializer.Serialize(localDict.Global_vars);
                        File.WriteAllText(AppSettings.GlobalVarsPath, str);
                        WeakReferenceMessenger.Default.Send(new AcGlobalsMessage(localDict.Global_vars));
                    }
                    Dictionary<string, Match> dict = new();
                    foreach (var match in localDict.Matches)
                    {
                        if (match.Triggers is not null && match.Triggers.Count > 0)
                        {
                            foreach (var t in match.Triggers)
                            {
                                var clone = new Match(match) { Trigger = t };
                                dict[t] = clone;
                            }
                        }
                        else if (!string.IsNullOrEmpty(match.Trigger))
                        {
                            dict[match.Trigger] = match;
                        }
                        else if (!string.IsNullOrEmpty(match.Regex))
                        {
                            dict[$"__regex_{match.Regex}"] = match;
                        }
                    }
                    var jsonStr = JsonSerializer.Serialize(dict);
                    File.WriteAllText(AppSettings.DictPath, jsonStr);
                    SendMessage("Reset", new Match());
                    Intent resultIntent = new Intent("com.dingleinc.texttoolspro.CONFIG_RESULT");
                    resultIntent.PutExtra("status", 0); // or 1 for failure
                    context.SendBroadcast(resultIntent);

                }
                catch (Exception e)
                {
                    Intent resultIntent = new Intent("com.dingleinc.texttoolspro.CONFIG_RESULT");
                    resultIntent.PutExtra("status", e.Message); // or 1 for failure
                    context.SendBroadcast(resultIntent);
                }
            }

        }
    }
}