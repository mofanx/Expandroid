#if ANDROID
using Android.Content;
using AndroidX.Work;
using CommunityToolkit.Mvvm.Messaging;
using Expandroid.Models;
using Microsoft.Maui.Storage;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;

namespace Expandroid.Services
{
    public class SyncWorker : Worker
    {
        public SyncWorker(Context context, AndroidX.Work.WorkerParameters workerParams)
            : base(context, workerParams)
        {
        }

        public override Result DoWork()
        {
            try
            {
                var configPath = Path.Combine(FileSystem.Current.AppDataDirectory, "sync_config.json");
                if (!File.Exists(configPath))
                    return Result.InvokeSuccess();

                var configJson = File.ReadAllText(configPath);
                var config = JsonSerializer.Deserialize<SyncConfig>(configJson);
                if (config?.Method == SyncMethod.None || string.IsNullOrEmpty(config.SyncUri))
                    return Result.InvokeSuccess();

                var yamlWorkspace = new YamlWorkspace(new SafManager(Android.App.Application.Context));
                var syncManager = new SyncManager(yamlWorkspace);
                syncManager.UpdateConfig(config);

                var dictPath = AppSettings.DictPath;
                if (!File.Exists(dictPath))
                    return Result.InvokeSuccess();

                var dict = JsonSerializer.Deserialize<Dictionary<string, Match>>(File.ReadAllText(dictPath));
                List<Var> globalVars = null;
                if (File.Exists(AppSettings.GlobalVarsPath))
                    globalVars = JsonSerializer.Deserialize<List<Var>>(File.ReadAllText(AppSettings.GlobalVarsPath));

                var result = syncManager.SyncAsync(dict, globalVars).GetAwaiter().GetResult();

                if (result.Success && result.HasRemoteChanges && result.PulledDict != null)
                {
                    var isLww = config.ConflictStrategy == ConflictStrategy.LastWriteWins;
                    foreach (var kv in result.PulledDict)
                    {
                        if (!dict.ContainsKey(kv.Key))
                        {
                            dict[kv.Key] = kv.Value;
                        }
                        else if (isLww && !result.ConflictFiles.Contains(kv.Key))
                        {
                            dict[kv.Key] = kv.Value;
                        }

                        try
                        {
                            WeakReferenceMessenger.Default.Send(new AcServiceMessage(("Add", kv.Value)));
                        }
                        catch { }
                    }
                    if (result.PulledGlobalVars != null && result.PulledGlobalVars.Count > 0)
                    {
                        foreach (var v in result.PulledGlobalVars)
                        {
                            var idx = globalVars.FindIndex(g => g.Name == v.Name);
                            if (idx < 0)
                                globalVars.Add(v);
                            else if (isLww)
                                globalVars[idx] = v;
                        }
                        try
                        {
                            WeakReferenceMessenger.Default.Send(new AcGlobalsMessage(globalVars));
                        }
                        catch { }
                    }

                    var mergedJson = JsonSerializer.Serialize(dict);
                    File.WriteAllText(dictPath, mergedJson);
                    if (globalVars != null)
                    {
                        var gvJson = JsonSerializer.Serialize(globalVars);
                        File.WriteAllText(AppSettings.GlobalVarsPath, gvJson);
                    }
                }

                if (result.Success)
                    return Result.InvokeSuccess();
                else
                    return Result.InvokeRetry();
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"SyncWorker failed: {ex.Message}");
                return Result.InvokeRetry();
            }
        }

        public static void SchedulePeriodicSync(int intervalMinutes = 15)
        {
            var constraints = new Constraints.Builder()
                .SetRequiredNetworkType(NetworkType.Connected)
                .Build();

            var request = new PeriodicWorkRequest.Builder(
                Java.Lang.Class.FromType(typeof(SyncWorker)),
                intervalMinutes, Java.Util.Concurrent.TimeUnit.Minutes)
                .SetConstraints(constraints)
                .Build();

            WorkManager.GetInstance(Android.App.Application.Context)
                .EnqueueUniquePeriodicWork(
                    "expandroid-sync",
                    ExistingPeriodicWorkPolicy.Keep,
                    request);
        }

        public static void CancelPeriodicSync()
        {
            WorkManager.GetInstance(Android.App.Application.Context)
                .CancelUniqueWork("expandroid-sync");
        }
    }
}
#endif
