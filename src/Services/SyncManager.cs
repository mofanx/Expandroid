using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Expandroid.Models;
using Microsoft.Maui.Storage;

namespace Expandroid.Services
{
    public enum SyncMethod
    {
        None,
        CloudFolder,
        Syncthing,
        WebDAV,
        Git,
        Local,
        Manual
    }

    public enum ConflictStrategy
    {
        LastWriteWins,
        KeepBoth
    }

    public enum SyncStatus
    {
        Idle,
        Syncing,
        Error,
        Conflict
    }

    public class SyncState
    {
        [JsonPropertyName("version")]
        public int Version { get; set; } = 1;
        [JsonPropertyName("lastSyncTime")]
        public DateTime? LastSyncTime { get; set; }
        [JsonPropertyName("files")]
        public Dictionary<string, FileSyncEntry> Files { get; set; } = new();
    }

    public class FileSyncEntry
    {
        [JsonPropertyName("hash")]
        public string Hash { get; set; }
        [JsonPropertyName("size")]
        public long Size { get; set; }
        [JsonPropertyName("lastModified")]
        public DateTime LastModified { get; set; }
    }

    public class SyncResult
    {
        public bool Success { get; set; }
        public string ErrorMessage { get; set; }
        public int FilesSynced { get; set; }
        public int Conflicts { get; set; }
        public List<string> ConflictFiles { get; set; } = new();
        public SyncStatus Status { get; set; }
        public Dictionary<string, Match> PulledDict { get; set; }
        public List<Var> PulledGlobalVars { get; set; }
        public bool HasRemoteChanges { get; set; }
    }

    public class SyncConfig
    {
        public SyncMethod Method { get; set; } = SyncMethod.None;
        public string SyncUri { get; set; }
        public string Username { get; set; }
        public string Password { get; set; }
        public ConflictStrategy ConflictStrategy { get; set; } = ConflictStrategy.LastWriteWins;
        public int ForegroundPollIntervalSec { get; set; } = 60;
        public int BackgroundPollIntervalMin { get; set; } = 15;
        public bool WifiOnly { get; set; } = false;
    }

    public class SyncManager
    {
        private readonly YamlWorkspace _yamlWorkspace;
        private SyncConfig _config;
        private SyncState _state;
        private readonly string _stateFilePath;
        private readonly string _syncStateFileName = ".expandroid-sync.json";

        public SyncStatus CurrentStatus { get; private set; } = SyncStatus.Idle;
        public DateTime? LastSyncTime => _state?.LastSyncTime;
        public event Action<SyncStatus, SyncResult> SyncCompleted;

        public SyncManager(YamlWorkspace yamlWorkspace)
        {
            _yamlWorkspace = yamlWorkspace;
            _stateFilePath = Path.Combine(FileSystem.Current.AppDataDirectory, "sync_state.json");
            _state = LoadState();
            _config = LoadConfig();
        }

        public SyncConfig GetConfig() => _config;

        public void UpdateConfig(SyncConfig config)
        {
            _config = config;
            SaveConfig(config);
        }

        public bool IsSafUri()
        {
            return _config.SyncUri?.StartsWith("content://") == true;
        }

        #region Push

        public async Task<SyncResult> PushAsync(Dictionary<string, Match> dict, List<Var> globalVars = null, CancellationToken ct = default)
        {
            var result = new SyncResult();
            if (_config.Method == SyncMethod.None || string.IsNullOrEmpty(_config.SyncUri))
            {
                result.ErrorMessage = "Sync not configured";
                return result;
            }

            try
            {
                CurrentStatus = SyncStatus.Syncing;
                var syncFolder = ResolveSyncFolder();
                if (syncFolder == null)
                {
                    result.ErrorMessage = "Cannot resolve sync folder";
                    CurrentStatus = SyncStatus.Error;
                    return result;
                }

                await _yamlWorkspace.WriteToFolderAsync(syncFolder, dict, globalVars, ct);
                await SaveSyncStateToFolderAsync(syncFolder, ct);
                UpdateStateAfterSync(syncFolder);

                result.Success = true;
                result.Status = SyncStatus.Idle;
                CurrentStatus = SyncStatus.Idle;
                SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
            catch (Exception ex)
            {
                result.ErrorMessage = ex.Message;
                result.Status = SyncStatus.Error;
                CurrentStatus = SyncStatus.Error;
                SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
        }

        #endregion

        #region Pull

        public async Task<SyncResult> PullAsync(CancellationToken ct = default)
        {
            var result = new SyncResult();
            if (_config.Method == SyncMethod.None || string.IsNullOrEmpty(_config.SyncUri))
            {
                result.ErrorMessage = "Sync not configured";
                return result;
            }

            try
            {
                CurrentStatus = SyncStatus.Syncing;
                var syncFolder = ResolveSyncFolder();
                if (syncFolder == null)
                {
                    result.ErrorMessage = "Sync folder not accessible";
                    CurrentStatus = SyncStatus.Error;
                    return result;
                }

                var remoteFiles = _yamlWorkspace.GetFileList(syncFolder);
                if (remoteFiles.Count == 0)
                {
                    result.Success = true;
                    result.Status = SyncStatus.Idle;
                    CurrentStatus = SyncStatus.Idle;
                    SyncCompleted?.Invoke(CurrentStatus, result);
                    return result;
                }

                int filesSynced = 0;
                var conflicts = new List<string>();
                var hasRemoteChanges = false;

                var localDictPath = AppSettings.DictPath;
                var localDictExists = File.Exists(localDictPath);
                var localDictLastModified = localDictExists
                    ? new System.IO.FileInfo(localDictPath).LastWriteTimeUtc
                    : DateTime.MinValue;

                foreach (var remoteFile in remoteFiles)
                {
                    ct.ThrowIfCancellationRequested();
                    var relativePath = GetRelativePath(remoteFile.Path, syncFolder);
                    var hasLocalState = _state.Files.TryGetValue(relativePath, out var localEntry);
                    var remoteChanged = !hasLocalState || localEntry.Hash != remoteFile.Hash;

                    if (remoteChanged)
                    {
                        hasRemoteChanges = true;
                        filesSynced++;

                        if (hasLocalState && localDictExists)
                        {
                            var localModifiedSinceSync = localEntry.LastModified < localDictLastModified;
                            if (localModifiedSinceSync)
                            {
                                if (_config.ConflictStrategy == ConflictStrategy.KeepBoth)
                                {
                                    conflicts.Add(relativePath);
                                }
                                else
                                {
                                    if (remoteFile.LastModified > localEntry.LastModified)
                                        conflicts.Add(relativePath);
                                }
                            }
                        }
                    }
                }

                if (hasRemoteChanges)
                {
                    var (pulledDict, pulledVars) = await ReadSyncedDataAsync(ct);
                    result.PulledDict = pulledDict;
                    result.PulledGlobalVars = pulledVars;
                    result.HasRemoteChanges = true;
                }

                UpdateStateAfterSync(syncFolder);
                await SaveSyncStateToFolderAsync(syncFolder, ct);

                result.Success = true;
                result.FilesSynced = filesSynced;
                result.Conflicts = conflicts.Count;
                result.ConflictFiles = conflicts;
                result.Status = conflicts.Count > 0 ? SyncStatus.Conflict : SyncStatus.Idle;
                CurrentStatus = result.Status;
                SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
            catch (Exception ex)
            {
                result.ErrorMessage = ex.Message;
                result.Status = SyncStatus.Error;
                CurrentStatus = SyncStatus.Error;
                SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
        }

        #endregion

        #region Sync (Pull + Merge + Push)

        public async Task<SyncResult> SyncAsync(Dictionary<string, Match> dict, List<Var> globalVars = null, CancellationToken ct = default)
        {
            var pullResult = await PullAsync(ct);
            if (!pullResult.Success)
                return pullResult;

            if (pullResult.HasRemoteChanges && pullResult.PulledDict != null)
            {
                var isLww = _config.ConflictStrategy == ConflictStrategy.LastWriteWins;

                foreach (var kv in pullResult.PulledDict)
                {
                    if (!dict.ContainsKey(kv.Key))
                    {
                        dict[kv.Key] = kv.Value;
                    }
                    else if (isLww && !pullResult.ConflictFiles.Contains(kv.Key))
                    {
                        dict[kv.Key] = kv.Value;
                    }
                }

                if (pullResult.PulledGlobalVars != null && pullResult.PulledGlobalVars.Count > 0)
                {
                    globalVars ??= new List<Var>();
                    var byName = globalVars.ToDictionary(v => v.Name);
                    foreach (var v in pullResult.PulledGlobalVars)
                    {
                        if (!byName.ContainsKey(v.Name))
                        {
                            globalVars.Add(v);
                            byName[v.Name] = v;
                        }
                        else if (isLww)
                        {
                            var idx = globalVars.FindIndex(g => g.Name == v.Name);
                            if (idx >= 0)
                                globalVars[idx] = v;
                        }
                    }
                }
            }

            return await PushAsync(dict, globalVars, ct);
        }

        #endregion

        #region Change Detection

        public bool CheckChanges()
        {
            var syncFolder = ResolveSyncFolder();
            if (syncFolder == null)
                return false;

            var remoteFiles = _yamlWorkspace.GetFileList(syncFolder);
            foreach (var remoteFile in remoteFiles)
            {
                var relativePath = GetRelativePath(remoteFile.Path, syncFolder);
                if (!_state.Files.TryGetValue(relativePath, out var localEntry) || localEntry.Hash != remoteFile.Hash)
                    return true;
            }
            return false;
        }

        #endregion

        #region Read Synced Data

        public async Task<(Dictionary<string, Match> dict, List<Var> globalVars)> ReadSyncedDataAsync(CancellationToken ct = default)
        {
            var syncFolder = ResolveSyncFolder();
            if (syncFolder == null)
                return (new Dictionary<string, Match>(), new List<Var>());

            return await _yamlWorkspace.ReadFromFolderWithImportsAsync(syncFolder, ct);
        }

        #endregion

        #region Conflict Resolution

        public List<string> GetConflictFiles()
        {
            return _state.Files.Keys
                .Where(k => k.StartsWith("local_"))
                .Select(k => k.Substring("local_".Length))
                .ToList();
        }

        public void ResolveConflict(string fileName, bool keepLocal)
        {
            var syncFolder = ResolveSyncFolder();
            if (syncFolder == null) return;

            var localKey = "local_" + fileName;
            if (keepLocal)
            {
                _state.Files.Remove(localKey);
                if (_state.Files.ContainsKey(fileName))
                    _state.Files.Remove(fileName);
            }
            else
            {
                _state.Files.Remove(localKey);
            }
            SaveState();
        }

        #endregion

        #region Path Resolution

        private string ResolveSyncFolder()
        {
            if (string.IsNullOrEmpty(_config.SyncUri))
                return null;

            if (IsSafUri())
                return _config.SyncUri;

            if (_config.Method == SyncMethod.CloudFolder ||
                _config.Method == SyncMethod.Syncthing ||
                _config.Method == SyncMethod.Local)
            {
                if (Directory.Exists(_config.SyncUri))
                    return _config.SyncUri;
            }
            return null;
        }

        private static string GetRelativePath(string fullPath, string basePath)
        {
            if (string.IsNullOrEmpty(basePath))
                return GetLastSegment(fullPath);

            if (basePath.StartsWith("content://"))
            {
                if (fullPath.StartsWith(basePath, StringComparison.OrdinalIgnoreCase))
                {
                    var rest = fullPath.Substring(basePath.Length).TrimStart('/');
                    return string.IsNullOrEmpty(rest) ? GetLastSegment(fullPath) : rest;
                }
                return GetLastSegment(fullPath);
            }

            var normalizedBase = basePath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
                + Path.DirectorySeparatorChar;
            if (fullPath.StartsWith(normalizedBase, StringComparison.OrdinalIgnoreCase))
                return fullPath.Substring(normalizedBase.Length);

            return Path.GetFileName(fullPath);
        }

        private static string GetLastSegment(string path)
        {
            if (string.IsNullOrEmpty(path)) return "";
            var idx = path.LastIndexOfAny(new[] { '/', '\\' });
            return idx >= 0 ? path.Substring(idx + 1) : path;
        }

        #endregion

        #region State Persistence

        private void UpdateStateAfterSync(string syncFolder)
        {
            var files = _yamlWorkspace.GetFileList(syncFolder);
            _state.Files.Clear();
            foreach (var f in files)
            {
                var relativePath = GetRelativePath(f.Path, syncFolder);
                _state.Files[relativePath] = new FileSyncEntry
                {
                    Hash = f.Hash,
                    Size = f.Size,
                    LastModified = f.LastModified
                };
            }
            _state.LastSyncTime = DateTime.UtcNow;
            SaveState();
        }

        private async Task SaveSyncStateToFolderAsync(string folder, CancellationToken ct)
        {
            try
            {
                var json = JsonSerializer.Serialize(_state, new JsonSerializerOptions
                {
                    WriteIndented = true,
                    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
                });

                if (IsSafUri())
                {
#if ANDROID
                    var safManager = new SafManager(Android.App.Application.Context);
                    await safManager.WriteTextFileAsync(folder, _syncStateFileName, json);
#endif
                    return;
                }

                var statePath = Path.Combine(folder, _syncStateFileName);
                await File.WriteAllTextAsync(statePath, json, ct);
            }
            catch { }
        }

        private SyncState LoadState()
        {
            try
            {
                if (File.Exists(_stateFilePath))
                {
                    var json = File.ReadAllText(_stateFilePath);
                    return JsonSerializer.Deserialize<SyncState>(json) ?? new SyncState();
                }
            }
            catch { }
            return new SyncState();
        }

        private void SaveState()
        {
            try
            {
                var json = JsonSerializer.Serialize(_state, new JsonSerializerOptions
                {
                    WriteIndented = true,
                    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
                });
                File.WriteAllText(_stateFilePath, json);
            }
            catch { }
        }

        private SyncConfig LoadConfig()
        {
            try
            {
                var configPath = Path.Combine(FileSystem.Current.AppDataDirectory, "sync_config.json");
                if (File.Exists(configPath))
                {
                    var json = File.ReadAllText(configPath);
                    return JsonSerializer.Deserialize<SyncConfig>(json) ?? new SyncConfig();
                }
            }
            catch { }
            return new SyncConfig();
        }

        private void SaveConfig(SyncConfig config)
        {
            try
            {
                var configPath = Path.Combine(FileSystem.Current.AppDataDirectory, "sync_config.json");
                var json = JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(configPath, json);
            }
            catch { }
        }

        #endregion
    }
}
