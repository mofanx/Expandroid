using System;
using Microsoft.Maui.Storage;

namespace Expandroid.Services
{
    /// <summary>
    /// Manages Git Personal Access Tokens (PAT) using secure platform storage.
    /// Uses SecureStorage on Android (backed by Android Keystore).
    /// </summary>
    public class CredentialManager
    {
        private const string PatKeyPrefix = "git_pat_";
        private const string GitUsernameKey = "git_username";

        public void SavePat(string repoUrl, string pat)
        {
            if (string.IsNullOrEmpty(repoUrl)) return;
            var key = PatKeyPrefix + NormalizeUrl(repoUrl);
            try
            {
                SecureStorage.Default.SetAsync(key, pat ?? "").GetAwaiter().GetResult();
            }
            catch { }
        }

        public string GetPat(string repoUrl)
        {
            if (string.IsNullOrEmpty(repoUrl)) return null;
            var key = PatKeyPrefix + NormalizeUrl(repoUrl);
            try
            {
                return SecureStorage.Default.GetAsync(key).GetAwaiter().GetResult();
            }
            catch { return null; }
        }

        public void DeletePat(string repoUrl)
        {
            if (string.IsNullOrEmpty(repoUrl)) return;
            var key = PatKeyPrefix + NormalizeUrl(repoUrl);
            SecureStorage.Default.Remove(key);
        }

        public void SaveGitUsername(string username)
        {
            try
            {
                SecureStorage.Default.SetAsync(GitUsernameKey, username ?? "").GetAwaiter().GetResult();
            }
            catch { }
        }

        public string GetGitUsername()
        {
            return SecureStorage.Default.GetAsync(GitUsernameKey).GetAwaiter().GetResult();
        }

        /// <summary>
        /// Builds an authenticated URL by embedding username:token into the HTTPS URL.
        /// e.g. https://github.com/user/repo.git → https://username:token@github.com/user/repo.git
        /// </summary>
        public string BuildAuthenticatedUrl(string repoUrl, string username, string pat)
        {
            if (string.IsNullOrEmpty(repoUrl) || string.IsNullOrEmpty(pat))
                return repoUrl;

            if (!repoUrl.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                return repoUrl;

            var withoutScheme = repoUrl.Substring("https://".Length);
            var encodedToken = Uri.EscapeDataString(pat);
            var encodedUser = Uri.EscapeDataString(username ?? "x-access-token");
            return $"https://{encodedUser}:{encodedToken}@{withoutScheme}";
        }

        private static string NormalizeUrl(string url)
        {
            try
            {
                var uri = new Uri(url);
                return $"{uri.Host}{uri.AbsolutePath}".TrimEnd('/').ToLowerInvariant();
            }
            catch
            {
                return url.ToLowerInvariant();
            }
        }
    }
}
