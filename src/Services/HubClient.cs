using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Expandroid.Models;
using Microsoft.Maui.Storage;

namespace Expandroid.Services
{
    public class HubClient : IDisposable
    {
        private const string PackageIndexUrl = "https://github.com/espanso/hub/releases/latest/download/package_index.json";
        private const string PackageDownloadBaseUrl = "https://github.com/espanso/hub/releases/download/{0}/{1}.zip";
        private const string CacheFileName = "hub_package_index.json";
        private const int CacheValidityMinutes = 60;

        private readonly HttpClient _httpClient;
        private readonly YamlWorkspace _yamlWorkspace;
        private readonly string _cachePath;
        private readonly string _installedPackagesPath;
        private HubPackageIndex _cachedIndex;

        public HubClient(YamlWorkspace yamlWorkspace)
        {
            _yamlWorkspace = yamlWorkspace;
            _httpClient = new HttpClient
            {
                Timeout = TimeSpan.FromSeconds(30)
            };
            _httpClient.DefaultRequestHeaders.UserAgent.ParseAdd("Expandroid/2.0");

            var appData = FileSystem.Current.AppDataDirectory;
            _cachePath = Path.Combine(appData, CacheFileName);
            _installedPackagesPath = Path.Combine(appData, "installed_packages.json");
        }

        public async Task<HubPackageIndex> GetPackageIndexAsync(bool forceRefresh = false, CancellationToken ct = default)
        {
            if (!forceRefresh && _cachedIndex != null)
                return _cachedIndex;

            if (!forceRefresh && TryLoadCachedIndex(out _cachedIndex))
                return _cachedIndex;

            try
            {
                var json = await _httpClient.GetStringAsync(PackageIndexUrl, ct);
                _cachedIndex = ParsePackageIndex(json);
                _cachedIndex.LastUpdated = DateTime.UtcNow;
                SaveCachedIndex(_cachedIndex);
                return _cachedIndex;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"GetPackageIndexAsync failed: {ex.Message}");
                return _cachedIndex ?? new HubPackageIndex();
            }
        }

        public async Task<InstalledPackage> InstallPackageAsync(HubPackageInfo package, CancellationToken ct = default)
        {
            if (package == null || string.IsNullOrEmpty(package.Name))
                return null;

            try
            {
                var downloadUrl = string.IsNullOrEmpty(package.DownloadUrl)
                    ? string.Format(PackageDownloadBaseUrl, package.Version ?? "latest", package.Name)
                    : package.DownloadUrl;

                var zipBytes = await _httpClient.GetByteArrayAsync(downloadUrl, ct);

                if (!string.IsNullOrEmpty(package.Sha256))
                {
                    using var sha256 = SHA256.Create();
                    var hashBytes = sha256.ComputeHash(zipBytes);
                    var actualHash = BitConverter.ToString(hashBytes).Replace("-", "").ToLowerInvariant();
                    if (!string.Equals(actualHash, package.Sha256.ToLowerInvariant(), StringComparison.OrdinalIgnoreCase))
                        throw new InvalidDataException($"SHA256 mismatch for package '{package.Name}': expected {package.Sha256}, got {actualHash}");
                }

                var tempZipPath = Path.Combine(Path.GetTempPath(), $"{package.Name}.zip");
                await File.WriteAllBytesAsync(tempZipPath, zipBytes, ct);

                var extractDir = Path.Combine(FileSystem.Current.AppDataDirectory, "packages", package.Name);
                if (Directory.Exists(extractDir))
                    Directory.Delete(extractDir, true);
                Directory.CreateDirectory(extractDir);

                ZipFile.ExtractToDirectory(tempZipPath, extractDir, true);

                var matchFiles = new List<string>();
                foreach (var file in Directory.GetFiles(extractDir, "*.yml", SearchOption.AllDirectories))
                {
                    matchFiles.Add(file);
                }

                var installed = new InstalledPackage
                {
                    Name = package.Name,
                    Version = package.Version,
                    Author = package.Author,
                    Description = package.Description,
                    InstalledDate = DateTime.UtcNow,
                    MatchFiles = matchFiles,
                    Sha256 = package.Sha256
                };

                SaveInstalledPackage(installed);

                try { File.Delete(tempZipPath); } catch { }

                return installed;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"InstallPackageAsync failed: {ex.Message}");
                throw;
            }
        }

        public List<InstalledPackage> GetInstalledPackages()
        {
            try
            {
                if (File.Exists(_installedPackagesPath))
                {
                    var json = File.ReadAllText(_installedPackagesPath);
                    return JsonSerializer.Deserialize<List<InstalledPackage>>(json) ?? new List<InstalledPackage>();
                }
            }
            catch { }
            return new List<InstalledPackage>();
        }

        public void UninstallPackage(string name)
        {
            var packages = GetInstalledPackages();
            var pkg = packages.FirstOrDefault(p => p.Name == name);
            if (pkg == null) return;

            var extractDir = Path.Combine(FileSystem.Current.AppDataDirectory, "packages", name);
            if (Directory.Exists(extractDir))
            {
                try { Directory.Delete(extractDir, true); } catch { }
            }

            packages.Remove(pkg);
            SaveInstalledPackages(packages);
        }

        public bool IsPackageInstalled(string name)
        {
            return GetInstalledPackages().Any(p => p.Name == name);
        }

        public async Task<List<Match>> GetPackageMatchesAsync(string packageName)
        {
            var packages = GetInstalledPackages();
            var pkg = packages.FirstOrDefault(p => p.Name == packageName);
            if (pkg == null) return new List<Match>();

            var matches = new List<Match>();

            foreach (var file in pkg.MatchFiles)
            {
                if (!File.Exists(file)) continue;
                var group = await _yamlWorkspace.ReadFileAsync(file);
                if (group.Matches != null)
                    matches.AddRange(group.Matches);
            }

            return matches;
        }

        private HubPackageIndex ParsePackageIndex(string json)
        {
            var result = new HubPackageIndex();
            try
            {
                using var doc = JsonDocument.Parse(json);
                if (doc.RootElement.TryGetProperty("packages", out var packagesElem))
                {
                    foreach (var pkgElem in packagesElem.EnumerateArray())
                    {
                        var info = new HubPackageInfo();
                        if (pkgElem.TryGetProperty("name", out var nameElem))
                            info.Name = nameElem.GetString();
                        if (pkgElem.TryGetProperty("title", out var titleElem))
                            info.Title = titleElem.GetString();
                        if (pkgElem.TryGetProperty("description", out var descElem))
                            info.Description = descElem.GetString();
                        if (pkgElem.TryGetProperty("author", out var authorElem))
                            info.Author = authorElem.GetString();
                        if (pkgElem.TryGetProperty("version", out var versionElem))
                            info.Version = versionElem.GetString();
                        if (pkgElem.TryGetProperty("homepage", out var homeElem))
                            info.HomePage = homeElem.GetString();
                        if (pkgElem.TryGetProperty("tags", out var tagsElem))
                            info.Tags = tagsElem.GetString();
                        if (pkgElem.TryGetProperty("thumbnail", out var thumbElem))
                            info.ThumbnailUrl = thumbElem.GetString();
                        if (pkgElem.TryGetProperty("sha256", out var shaElem))
                            info.Sha256 = shaElem.GetString();

                        result.Packages.Add(info);
                    }
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"ParsePackageIndex failed: {ex.Message}");
            }
            return result;
        }

        private bool TryLoadCachedIndex(out HubPackageIndex index)
        {
            index = null;
            try
            {
                if (!File.Exists(_cachePath))
                    return false;

                var json = File.ReadAllText(_cachePath);
                index = JsonSerializer.Deserialize<HubPackageIndex>(json);
                if (index == null) return false;

                var age = DateTime.UtcNow - index.LastUpdated;
                if (age.TotalMinutes > CacheValidityMinutes)
                    return false;

                return true;
            }
            catch { return false; }
        }

        private void SaveCachedIndex(HubPackageIndex index)
        {
            try
            {
                var json = JsonSerializer.Serialize(index, new JsonSerializerOptions
                {
                    WriteIndented = true,
                    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
                });
                File.WriteAllText(_cachePath, json);
            }
            catch { }
        }

        private void SaveInstalledPackage(InstalledPackage package)
        {
            var packages = GetInstalledPackages();
            packages.RemoveAll(p => p.Name == package.Name);
            packages.Add(package);
            SaveInstalledPackages(packages);
        }

        private void SaveInstalledPackages(List<InstalledPackage> packages)
        {
            try
            {
                var json = JsonSerializer.Serialize(packages, new JsonSerializerOptions
                {
                    WriteIndented = true,
                    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
                });
                File.WriteAllText(_installedPackagesPath, json);
            }
            catch { }
        }

        public void Dispose()
        {
            _httpClient?.Dispose();
        }
    }
}
