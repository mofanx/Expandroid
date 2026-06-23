#if ANDROID
using Android.Content;
using AndroidX.Work;
using CommunityToolkit.Mvvm.Messaging;
using Expandroid.Models;
using Microsoft.Maui.Storage;
using System;
using System.Collections.Generic;
using System.IO;
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
                var snapshotManager = new SnapshotManager();
                var mergeService = new ThreeWayMergeService(snapshotManager);
                var credentialManager = new CredentialManager();
                var syncManager = new SyncManager(yamlWorkspace, snapshotManager, mergeService, credentialManager);
                syncManager.UpdateConfig(config);

                var dictPath = AppSettings.DictPath;
                if (!File.Exists(dictPath))
                    return Result.InvokeSuccess();

                var dict = JsonSerializer.Deserialize<Dictionary<string, Match>>(File.ReadAllText(dictPath));
                List<Var> globalVars = null;
                if (File.Exists(AppSettings.GlobalVarsPath))
                    globalVars = JsonSerializer.Deserialize<List<Var>>(File.ReadAllText(AppSettings.GlobalVarsPath));

                var result = syncManager.SyncAsync(dict, globalVars).GetAwaiter().GetResult();

                if (result.Success && result.HasRemoteChanges)
                {
                    foreach (var kv in dict)
                    {
                        try
                        {
                            WeakReferenceMessenger.Default.Send(new AcServiceMessage(("Update", kv.Value)));
                        }
                        catch { }
                    }
                    var finalVars = result.MergedGlobalVars ?? globalVars;
                    if (finalVars != null)
                    {
                        try
                        {
                            WeakReferenceMessenger.Default.Send(new AcGlobalsMessage(finalVars));
                        }
                        catch { }
                    }

                    var mergedJson = JsonSerializer.Serialize(dict);
                    File.WriteAllText(dictPath, mergedJson);
                    if (finalVars != null)
                    {
                        var gvJson = JsonSerializer.Serialize(finalVars);
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
