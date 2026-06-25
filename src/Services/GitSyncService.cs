using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using EspansoGo.Models;
using Microsoft.Maui.Storage;

namespace EspansoGo.Services
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
        private string _branch = "main";

        public string LocalRepoPath => _localRepoPath;

        public GitSyncService(CredentialManager credentialManager, YamlWorkspace yamlWorkspace)
        {
            _credentialManager = credentialManager;
            _yamlWorkspace = yamlWorkspace;
            _localRepoPath = Path.Combine(FileSystem.Current.AppDataDirectory, "git_repo");
        }

        public void Configure(string repoUrl, string username, string pat, string branch = "main")
        {
            _repoUrl = repoUrl;
            _branch = string.IsNullOrEmpty(branch) ? "main" : branch;
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
                    var mergeOk = await RunGitAsync($"merge origin/{_branch} --no-edit", _localRepoPath, ct);
                    if (!mergeOk) return false;
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

                var grouped = _yamlWorkspace.GroupMatchesBySourceFile(dict);
                foreach (var (fileName, matches) in grouped)
                {
                    var group = new MatchGroup { Matches = matches };
                    var yaml = _yamlWorkspace.SerializeMatchGroup(group);
                    await File.WriteAllTextAsync(Path.Combine(matchDir, fileName), yaml, ct);
                }

                if (globalVars != null && globalVars.Count > 0)
                {
                    var gvGroup = new MatchGroup { GlobalVars = globalVars };
                    var gvYaml = _yamlWorkspace.SerializeMatchGroup(gvGroup);
                    await File.WriteAllTextAsync(Path.Combine(matchDir, "global_vars.yml"), gvYaml, ct);
                }

                await RunGitAsync("add -A", _localRepoPath, ct);
                var timestamp = DateTime.UtcNow.ToString("yyyy-MM-dd HH:mm:ss");
                await RunGitAsync($"commit -m \"espansogo sync {timestamp}\" --allow-empty", _localRepoPath, ct);
                await RunGitAsync($"push origin {_branch}", _localRepoPath, ct);
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
                var output = await RunGitCaptureAsync($"log HEAD..origin/{_branch} --oneline", _localRepoPath, ct);
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
            var resultFile = Path.Combine(FileSystem.Current.CacheDirectory, $"git_result_{Guid.NewGuid():N}.txt");
            if (File.Exists(resultFile)) File.Delete(resultFile);

            var escapedArgs = args.Replace("'", "'\\''");
            var wrapperScript = $"git {escapedArgs}; echo $? > '{resultFile}'";

            var intent = new Android.Content.Intent();
            intent.SetClassName("com.termux", "com.termux.app.RunCommandService");
            intent.SetAction("com.termux.RUN_COMMAND");
            intent.PutExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh");
            intent.PutExtra("com.termux.RUN_COMMAND_ARGUMENTS", new string[] { "-c", wrapperScript });
            intent.PutExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir);
            intent.PutExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
            intent.PutExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");

            AndroidX.Core.Content.ContextCompat.StartForegroundService(Android.App.Application.Context, intent);

            var timeout = TimeSpan.FromSeconds(30);
            var deadline = DateTime.UtcNow + timeout;
            while (DateTime.UtcNow < deadline)
            {
                await Task.Delay(1000, ct);
                if (File.Exists(resultFile))
                {
                    try
                    {
                        var content = await File.ReadAllTextAsync(resultFile, ct);
                        if (int.TryParse(content.Trim(), out var exitCode))
                        {
                            File.Delete(resultFile);
                            return exitCode == 0;
                        }
                    }
                    catch { }
                }
            }

            Debug.WriteLine($"RunGitViaTermuxAsync timed out waiting for result file: {args}");
            return false;
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
