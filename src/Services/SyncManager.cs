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
        public List<Var> MergedGlobalVars { get; set; }
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
        public string GitBranch { get; set; } = "main";
    }

    public class SyncManager
    {
        private readonly YamlWorkspace _yamlWorkspace;
        private readonly SnapshotManager _snapshotManager;
        private readonly ThreeWayMergeService _mergeService;
        private SyncConfig _config;
        private SyncState _state;
        private readonly string _stateFilePath;
        private readonly string _syncStateFileName = ".expandroid-sync.json";
        private WebDavClient _webDavClient;
        private GitSyncService _gitSyncService;
        private readonly CredentialManager _credentialManager;

        public static readonly SemaphoreSlim SyncLock = new(1, 1);
        private bool _suppressSyncCompleted = false;

        public SyncStatus CurrentStatus { get; private set; } = SyncStatus.Idle;
        public DateTime? LastSyncTime => _state?.LastSyncTime;
        public event Action<SyncStatus, SyncResult> SyncCompleted;
        public List<string> LastMergeWarnings { get; private set; } = new();

        public SyncManager(YamlWorkspace yamlWorkspace, SnapshotManager snapshotManager, ThreeWayMergeService mergeService, CredentialManager credentialManager)
        {
            _yamlWorkspace = yamlWorkspace;
            _snapshotManager = snapshotManager;
            _mergeService = mergeService;
            _credentialManager = credentialManager;
            _stateFilePath = Path.Combine(FileSystem.Current.AppDataDirectory, "sync_state.json");
            _state = LoadState();
            _config = LoadConfig();
            EnsureWebDavClient();
            EnsureGitSyncService();
        }

        private void EnsureWebDavClient()
        {
            if (_config.Method == SyncMethod.WebDAV && !string.IsNullOrEmpty(_config.SyncUri))
            {
                _webDavClient?.Dispose();
                _webDavClient = new WebDavClient(_config.SyncUri, _config.Username, _config.Password);
            }
            else
            {
                _webDavClient?.Dispose();
                _webDavClient = null;
            }
        }

        private void EnsureGitSyncService()
        {
            if (_config.Method == SyncMethod.Git && !string.IsNullOrEmpty(_config.SyncUri))
            {
                _gitSyncService ??= new GitSyncService(_credentialManager, _yamlWorkspace);
                var pat = _credentialManager.GetPat(_config.SyncUri);
                _gitSyncService.Configure(_config.SyncUri, _config.Username, pat ?? _config.Password, _config.GitBranch);
            }
            else
            {
                _gitSyncService = null;
            }
        }

        public bool IsWebDav() => _config.Method == SyncMethod.WebDAV;
        public bool IsGit() => _config.Method == SyncMethod.Git;

        public SyncConfig GetConfig() => _config;

        public void UpdateConfig(SyncConfig config)
        {
            _config = config;
            SaveConfig(config);
            EnsureWebDavClient();
            EnsureGitSyncService();
        }

        public bool IsSafUri()
        {
            return _config.SyncUri?.StartsWith("content://") == true;
        }

        public async Task<bool> TestConnectionAsync()
        {
            if (_config.Method == SyncMethod.WebDAV)
            {
                EnsureWebDavClient();
                return _webDavClient != null && await _webDavClient.TestConnectionAsync();
            }
            if (_config.Method == SyncMethod.Git)
            {
                return IsTermuxAvailable();
            }
            return false;
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

                if (IsWebDav())
                {
                    return await PushWebDavAsync(dict, globalVars, ct);
                }

                if (IsGit())
                {
                    return await PushGitAsync(dict, globalVars, ct);
                }

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
                if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
            catch (Exception ex)
            {
                result.ErrorMessage = ex.Message;
                result.Status = SyncStatus.Error;
                CurrentStatus = SyncStatus.Error;
                if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
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

                if (IsWebDav())
                {
                    return await PullWebDavAsync(ct);
                }

                if (IsGit())
                {
                    return await PullGitAsync(ct);
                }

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
                    if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
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
                if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
            catch (Exception ex)
            {
                result.ErrorMessage = ex.Message;
                result.Status = SyncStatus.Error;
                CurrentStatus = SyncStatus.Error;
                if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
        }

        #endregion

        #region Sync (Pull + Merge + Push)

        public async Task<SyncResult> SyncAsync(Dictionary<string, Match> dict, List<Var> globalVars = null, CancellationToken ct = default)
        {
            _suppressSyncCompleted = true;
            SyncResult finalResult;
            try
            {
            var pullResult = await PullAsync(ct);
            if (!pullResult.Success)
            {
                finalResult = pullResult;
                goto done;
            }

            if (pullResult.HasRemoteChanges && pullResult.PulledDict != null)
            {
                if (_snapshotManager.HasSnapshot())
                {
                    var mergeResult = _mergeService.Merge(dict, globalVars, pullResult.PulledDict, pullResult.PulledGlobalVars);
                    dict.Clear();
                    foreach (var kv in mergeResult.MergedDict)
                        dict[kv.Key] = kv.Value;
                    globalVars ??= new List<Var>();
                    globalVars.Clear();
                    globalVars.AddRange(mergeResult.MergedGlobalVars);
                    pullResult.MergedGlobalVars = globalVars;
                    LastMergeWarnings = mergeResult.Warnings;
                    pullResult.ConflictFiles = mergeResult.Conflicts;
                    pullResult.Conflicts = mergeResult.Conflicts.Count;
                }
                else
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
                        pullResult.MergedGlobalVars = globalVars;
                    }
                }
            }

            var pushResult = await PushAsync(dict, globalVars, ct);

            if (pushResult.Success)
            {
                _snapshotManager.CreateSnapshot(dict, globalVars);
            }

            pushResult.MergedGlobalVars = pullResult.MergedGlobalVars;
            pushResult.HasRemoteChanges = pullResult.HasRemoteChanges;
            pushResult.PulledDict = pullResult.PulledDict;
            pushResult.PulledGlobalVars = pullResult.PulledGlobalVars;
            pushResult.Conflicts = pullResult.Conflicts;
            pushResult.ConflictFiles = pullResult.ConflictFiles;

            finalResult = pushResult;
            done:
            _suppressSyncCompleted = false;
            if (!_suppressSyncCompleted) SyncCompleted?.Invoke(finalResult.Status, finalResult);
            return finalResult;
        }

        #endregion

        #region Change Detection

        public bool CheckChanges()
        {
            if (IsWebDav())
            {
                return CheckWebDavChanges();
            }

            if (IsGit())
            {
                return GitHasRemoteChangesAsync().GetAwaiter().GetResult();
            }

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

        private bool CheckWebDavChanges()
        {
            try
            {
                EnsureWebDavClient();
                if (_webDavClient == null) return false;
                var remoteFiles = _webDavClient.ListDirectoryAsync().GetAwaiter().GetResult();
                foreach (var f in remoteFiles)
                {
                    if (f.IsDirectory || f.DisplayName.StartsWith(".") || f.DisplayName.StartsWith("_"))
                        continue;
                    if (!f.DisplayName.EndsWith(".yml") && !f.DisplayName.EndsWith(".yaml"))
                        continue;
                    var hash = f.ETag ?? f.LastModified.ToString("o");
                    if (!_state.Files.TryGetValue(f.DisplayName, out var localEntry) || localEntry.Hash != hash)
                        return true;
                }
                return false;
            }
            catch { return false; }
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

        #region Git

        private async Task<SyncResult> PushGitAsync(Dictionary<string, Match> dict, List<Var> globalVars, CancellationToken ct)
        {
            var result = new SyncResult();
            try
            {
                EnsureGitSyncService();
                if (_gitSyncService == null)
                {
                    result.ErrorMessage = "Git sync service not initialized";
                    CurrentStatus = SyncStatus.Error;
                    return result;
                }

                var ok = await _gitSyncService.PushAsync(dict, globalVars, ct);
                if (!ok)
                {
                    result.ErrorMessage = "Git push failed";
                    CurrentStatus = SyncStatus.Error;
                    if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                    return result;
                }

                _state.LastSyncTime = DateTime.UtcNow;
                SaveState();

                result.Success = true;
                result.Status = SyncStatus.Idle;
                CurrentStatus = SyncStatus.Idle;
                if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
            catch (Exception ex)
            {
                result.ErrorMessage = ex.Message;
                result.Status = SyncStatus.Error;
                CurrentStatus = SyncStatus.Error;
                if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
        }

        private async Task<SyncResult> PullGitAsync(CancellationToken ct)
        {
            var result = new SyncResult();
            try
            {
                EnsureGitSyncService();
                if (_gitSyncService == null)
                {
                    result.ErrorMessage = "Git sync service not initialized";
                    CurrentStatus = SyncStatus.Error;
                    return result;
                }

                var pullOk = await _gitSyncService.PullAsync(ct);
                if (!pullOk)
                {
                    result.ErrorMessage = "Git pull failed";
                    CurrentStatus = SyncStatus.Error;
                    if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                    return result;
                }

                var (pulledDict, pulledVars) = await _gitSyncService.ReadRepoAsync(ct);

                result.Success = true;
                result.PulledDict = pulledDict;
                result.PulledGlobalVars = pulledVars;
                result.HasRemoteChanges = pulledDict.Count > 0 || (pulledVars != null && pulledVars.Count > 0);
                result.Status = SyncStatus.Idle;
                CurrentStatus = SyncStatus.Idle;
                if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
            catch (Exception ex)
            {
                result.ErrorMessage = ex.Message;
                result.Status = SyncStatus.Error;
                CurrentStatus = SyncStatus.Error;
                if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
        }

        public async Task<bool> GitHasRemoteChangesAsync(CancellationToken ct = default)
        {
            EnsureGitSyncService();
            if (_gitSyncService == null) return false;
            return await _gitSyncService.HasRemoteChangesAsync(ct);
        }

        public bool IsTermuxAvailable()
        {
#if ANDROID
            try
            {
                var pm = Android.App.Application.Context.PackageManager;
                var info = pm.GetPackageInfo("com.termux", (Android.Content.PM.PackageInfoFlags)0);
                return info != null;
            }
            catch { return false; }
#else
            return false;
#endif
        }

        #endregion

        #region WebDAV

        private async Task<SyncResult> PushWebDavAsync(Dictionary<string, Match> dict, List<Var> globalVars, CancellationToken ct)
        {
            var result = new SyncResult();
            try
            {
                EnsureWebDavClient();
                if (_webDavClient == null)
                {
                    result.ErrorMessage = "WebDAV client not initialized";
                    CurrentStatus = SyncStatus.Error;
                    return result;
                }

                var group = new MatchGroup
                {
                    Matches = dict.Values.ToList(),
                    GlobalVars = globalVars ?? new List<Var>()
                };
                var yaml = _yamlWorkspace.SerializeMatchGroup(group);
                await _webDavClient.PutFileAsync("expandroid.yml", yaml, ct);

                var stateJson = JsonSerializer.Serialize(_state, new JsonSerializerOptions
                {
                    WriteIndented = true,
                    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
                });
                await _webDavClient.PutFileAsync(_syncStateFileName, stateJson, ct);

                await UpdateWebDavStateAsync(ct);

                result.Success = true;
                result.Status = SyncStatus.Idle;
                CurrentStatus = SyncStatus.Idle;
                if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
            catch (Exception ex)
            {
                result.ErrorMessage = ex.Message;
                result.Status = SyncStatus.Error;
                CurrentStatus = SyncStatus.Error;
                if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
        }

        private async Task<SyncResult> PullWebDavAsync(CancellationToken ct)
        {
            var result = new SyncResult();
            try
            {
                EnsureWebDavClient();
                if (_webDavClient == null)
                {
                    result.ErrorMessage = "WebDAV client not initialized";
                    CurrentStatus = SyncStatus.Error;
                    return result;
                }

                var remoteFiles = await _webDavClient.ListDirectoryAsync("", ct);
                var yamlFiles = remoteFiles
                    .Where(f => !f.IsDirectory &&
                                (f.DisplayName.EndsWith(".yml") || f.DisplayName.EndsWith(".yaml")) &&
                                !f.DisplayName.StartsWith("_") &&
                                !f.DisplayName.StartsWith("."))
                    .ToList();

                if (yamlFiles.Count == 0)
                {
                    result.Success = true;
                    result.Status = SyncStatus.Idle;
                    CurrentStatus = SyncStatus.Idle;
                    if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
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

                var pulledDict = new Dictionary<string, Match>();
                var pulledVars = new List<Var>();
                var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                var remoteFileLookup = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                foreach (var f in remoteFiles)
                {
                    if (!f.IsDirectory && (f.DisplayName.EndsWith(".yml") || f.DisplayName.EndsWith(".yaml")))
                        remoteFileLookup[f.DisplayName] = f.Href;
                }

                foreach (var remoteFile in yamlFiles)
                {
                    ct.ThrowIfCancellationRequested();
                    var relativePath = remoteFile.DisplayName;
                    var hasLocalState = _state.Files.TryGetValue(relativePath, out var localEntry);
                    var remoteHash = remoteFile.ETag ?? remoteFile.LastModified.ToString("o");
                    var remoteChanged = !hasLocalState || localEntry.Hash != remoteHash;

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

                        await PullWebDavWithImportsRecursive(relativePath, remoteFileLookup, pulledDict, pulledVars, visited, 0, ct);
                    }
                }

                if (hasRemoteChanges)
                {
                    result.PulledDict = pulledDict;
                    result.PulledGlobalVars = pulledVars;
                    result.HasRemoteChanges = true;
                }

                await UpdateWebDavStateAsync(ct);

                result.Success = true;
                result.FilesSynced = filesSynced;
                result.Conflicts = conflicts.Count;
                result.ConflictFiles = conflicts;
                result.Status = conflicts.Count > 0 ? SyncStatus.Conflict : SyncStatus.Idle;
                CurrentStatus = result.Status;
                if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
            catch (Exception ex)
            {
                result.ErrorMessage = ex.Message;
                result.Status = SyncStatus.Error;
                CurrentStatus = SyncStatus.Error;
                if (!_suppressSyncCompleted) SyncCompleted?.Invoke(CurrentStatus, result);
                return result;
            }
        }

        private async Task PullWebDavWithImportsRecursive(
            string remotePath,
            Dictionary<string, string> remoteFileLookup,
            Dictionary<string, Match> dict,
            List<Var> vars,
            HashSet<string> visited,
            int depth,
            CancellationToken ct)
        {
            if (depth > 10 || visited.Contains(remotePath)) return;
            visited.Add(remotePath);

            var content = await _webDavClient.GetFileAsync(remotePath, ct);
            if (string.IsNullOrEmpty(content)) return;

            var group = _yamlWorkspace.ParseYaml(content, remotePath);
            _yamlWorkspace.MergeGroupIntoDict(dict, vars, group);

            if (group.Imports != null && group.Imports.Count > 0)
            {
                var baseDir = remotePath.Contains("/")
                    ? remotePath.Substring(0, remotePath.LastIndexOf('/') + 1)
                    : "";

                foreach (var importPath in group.Imports)
                {
                    ct.ThrowIfCancellationRequested();
                    if (string.IsNullOrEmpty(importPath)) continue;

                    var importName = importPath.Contains("/")
                        ? importPath.Substring(importPath.LastIndexOf('/') + 1)
                        : importPath;

                    if (remoteFileLookup.TryGetValue(importName, out var resolvedHref))
                    {
                        var resolvedPath = resolvedHref;
                        if (resolvedPath.StartsWith(_config.SyncUri, StringComparison.OrdinalIgnoreCase))
                            resolvedPath = resolvedPath.Substring(_config.SyncUri.Length).TrimStart('/');
                        await PullWebDavWithImportsRecursive(resolvedPath, remoteFileLookup, dict, vars, visited, depth + 1, ct);
                    }
                    else
                    {
                        var resolvedRelative = importPath.StartsWith("/")
                            ? importPath.TrimStart('/')
                            : baseDir + importPath;
                        if (await _webDavClient.GetFileInfoAsync(resolvedRelative, ct) is { } info && !info.IsDirectory)
                        {
                            await PullWebDavWithImportsRecursive(resolvedRelative, remoteFileLookup, dict, vars, visited, depth + 1, ct);
                        }
                    }
                }
            }
        }

        private async Task UpdateWebDavStateAsync(CancellationToken ct)
        {
            var remoteFiles = await _webDavClient.ListDirectoryAsync("", ct);
            _state.Files.Clear();
            foreach (var f in remoteFiles)
            {
                if (f.IsDirectory || f.DisplayName.StartsWith(".") || f.DisplayName.StartsWith("_"))
                    continue;
                if (!f.DisplayName.EndsWith(".yml") && !f.DisplayName.EndsWith(".yaml"))
                    continue;
                _state.Files[f.DisplayName] = new FileSyncEntry
                {
                    Hash = f.ETag ?? f.LastModified.ToString("o"),
                    Size = f.Size,
                    LastModified = f.LastModified
                };
            }
            _state.LastSyncTime = DateTime.UtcNow;
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

            if (_config.Method == SyncMethod.WebDAV)
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
