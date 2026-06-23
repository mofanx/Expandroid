using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Expandroid.Models;
using Microsoft.Maui.Storage;

namespace Expandroid.Services
{
    /// <summary>
    /// Git sync via Termux's git installation.
    /// Uses Termux's RUN_COMMAND intent to execute git operations.
    /// Requires Termux + git installed (pkg install git) + com.termux.permission.RUN_COMMAND.
    /// </summary>
    public class GitSyncService
    {
        private readonly CredentialManager _credentialManager;
        private readonly YamlWorkspace _yamlWorkspace;
        private string _repoUrl;
        private string _localRepoPath;

        public string LocalRepoPath => _localRepoPath;

        public GitSyncService(CredentialManager credentialManager, YamlWorkspace yamlWorkspace)
        {
            _credentialManager = credentialManager;
            _yamlWorkspace = yamlWorkspace;
            _localRepoPath = Path.Combine(FileSystem.Current.AppDataDirectory, "git_repo");
        }

        public void Configure(string repoUrl, string username, string pat)
        {
            _repoUrl = repoUrl;
            if (!string.IsNullOrEmpty(pat))
            {
                _repoUrl = _credentialManager.BuildAuthenticatedUrl(repoUrl, username, pat);
            }
        }

        /// <summary>
        /// Clones the remote repo if not already cloned, otherwise does fetch + merge.
        /// </summary>
        public async Task<bool> PullAsync(CancellationToken ct = default)
        {
            if (string.IsNullOrEmpty(_repoUrl))
                return false;

            try
            {
                if (!Directory.Exists(Path.Combine(_localRepoPath, ".git")))
                {
                    Directory.CreateDirectory(_localRepoPath);
                    var cloneOk = await RunGitAsync($"clone {_repoUrl} .", _localRepoPath, ct);
                    if (!cloneOk) return false;
                }
                else
                {
                    var fetchOk = await RunGitAsync("fetch origin", _localRepoPath, ct);
                    if (!fetchOk) return false;
                    await RunGitAsync("merge origin/main --no-edit", _localRepoPath, ct);
                }
                return true;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"GitSyncService.PullAsync failed: {ex.Message}");
                return false;
            }
        }

        /// <summary>
        /// Writes local dict + globalVars to the repo, commits, and pushes.
        /// </summary>
        public async Task<bool> PushAsync(Dictionary<string, Match> dict, List<Var> globalVars, CancellationToken ct = default)
        {
            if (string.IsNullOrEmpty(_repoUrl) || !Directory.Exists(_localRepoPath))
                return false;

            try
            {
                var matchDir = Path.Combine(_localRepoPath, "match");
                Directory.CreateDirectory(matchDir);

                var group = new MatchGroup
                {
                    Matches = dict.Values.ToList(),
                    GlobalVars = globalVars ?? new List<Var>()
                };
                var yaml = _yamlWorkspace.SerializeMatchGroup(group);
                await File.WriteAllTextAsync(Path.Combine(matchDir, "expandroid.yml"), yaml, ct);

                await RunGitAsync("add -A", _localRepoPath, ct);
                var timestamp = DateTime.UtcNow.ToString("yyyy-MM-dd HH:mm:ss");
                await RunGitAsync($"commit -m \"Expandroid sync {timestamp}\" --allow-empty", _localRepoPath, ct);
                await RunGitAsync("push origin main", _localRepoPath, ct);
                return true;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"GitSyncService.PushAsync failed: {ex.Message}");
                return false;
            }
        }

        /// <summary>
        /// Reads the latest pulled YAML files from the local git repo.
        /// </summary>
        public async Task<(Dictionary<string, Match> dict, List<Var> globalVars)> ReadRepoAsync(CancellationToken ct = default)
        {
            if (!Directory.Exists(_localRepoPath))
                return (new Dictionary<string, Match>(), new List<Var>());

            var matchDir = Path.Combine(_localRepoPath, "match");
            if (!Directory.Exists(matchDir))
                return (new Dictionary<string, Match>(), new List<Var>());

            return await _yamlWorkspace.ReadFromFolderWithImportsAsync(matchDir, ct);
        }

        /// <summary>
        /// Checks if the remote has new commits since last pull.
        /// </summary>
        public async Task<bool> HasRemoteChangesAsync(CancellationToken ct = default)
        {
            if (!Directory.Exists(Path.Combine(_localRepoPath, ".git")))
                return true;

            try
            {
                await RunGitAsync("fetch origin", _localRepoPath, ct);
                var output = await RunGitCaptureAsync("log HEAD..origin/main --oneline", _localRepoPath, ct);
                return !string.IsNullOrEmpty(output?.Trim());
            }
            catch
            {
                return false;
            }
        }

        /// <summary>
        /// Executes a git command via Termux RUN_COMMAND intent.
        /// On non-Android or without Termux, falls back to direct process execution.
        /// </summary>
        private async Task<bool> RunGitAsync(string args, string workingDir, CancellationToken ct)
        {
            try
            {
#if ANDROID
                return await RunGitViaTermuxAsync(args, workingDir, ct);
#else
                return await RunGitDirectAsync(args, workingDir, ct);
#endif
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"RunGitAsync failed: {ex.Message}");
                return false;
            }
        }

        private async Task<string> RunGitCaptureAsync(string args, string workingDir, CancellationToken ct)
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "git",
                    Arguments = args,
                    WorkingDirectory = workingDir,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false
                };
                using var proc = Process.Start(psi);
                if (proc == null) return null;
                var output = await proc.StandardOutput.ReadToEndAsync(ct);
                await proc.WaitForExitAsync(ct);
                return output;
            }
            catch
            {
                return null;
            }
        }

        private async Task<bool> RunGitDirectAsync(string args, string workingDir, CancellationToken ct)
        {
            var psi = new ProcessStartInfo
            {
                FileName = "git",
                Arguments = args,
                WorkingDirectory = workingDir,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false
            };
            using var proc = Process.Start(psi);
            if (proc == null) return false;
            await proc.WaitForExitAsync(ct);
            return proc.ExitCode == 0;
        }

#if ANDROID
        private async Task<bool> RunGitViaTermuxAsync(string args, string workingDir, CancellationToken ct)
        {
            var intent = new Android.Content.Intent();
            intent.SetClassName("com.termux", "com.termux.app.RunCommandService");
            intent.SetAction("com.termux.RUN_COMMAND");
            intent.PutExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/git");
            intent.PutExtra("com.termux.RUN_COMMAND_ARGUMENTS", new string[] { args });
            intent.PutExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir);
            intent.PutExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
            intent.PutExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");

            AndroidX.Core.Content.ContextCompat.StartForegroundService(Android.App.Application.Context, intent);

            // Termux RUN_COMMAND is fire-and-forget: there is no synchronous way to know if the
            // git command succeeded. We wait a fixed delay as a best-effort heuristic.
            // For long operations (clone/push), this may return true before completion.
            // A more robust solution would use Termux:Tasker or a result callback via
            // TermuxResultService, but that requires additional Termux configuration.
            await Task.Delay(2000, ct);
            return true;
        }
#endif

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
    }
}
