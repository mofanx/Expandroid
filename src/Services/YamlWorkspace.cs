using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Expandroid.Models;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace Expandroid.Services
{
    public class MatchGroup
    {
        public List<Match> Matches { get; set; } = new();
        public List<Var> GlobalVars { get; set; } = new();
        public List<string> Imports { get; set; } = new();
        public Dictionary<string, object> UnknownFields { get; set; } = new();
        public string SourceFile { get; set; }
    }

    public class SyncFileInfo
    {
        public string Path { get; set; }
        public long Size { get; set; }
        public DateTime LastModified { get; set; }
        public string Hash { get; set; }
    }

    public class ConfigSyncFields
    {
        public bool UseStandardIncludes { get; set; } = true;
        public List<string> Includes { get; set; } = new();
        public List<string> ExtraIncludes { get; set; } = new();
        public List<string> Excludes { get; set; } = new();
        public List<string> ExtraExcludes { get; set; } = new();
        public List<string> MatchPaths { get; set; } = new();
    }

    public class YamlWorkspace
    {
        private const int MaxImportDepth = 10;
        private static readonly string[] YamlExtensions = { ".yml", ".yaml" };

        private readonly IDeserializer _deserializer;
        private readonly ISerializer _serializer;
#if ANDROID
        private readonly SafManager _safManager;

        public YamlWorkspace() : this(null) { }

        public YamlWorkspace(SafManager safManager)
        {
            _safManager = safManager;
#else
        public YamlWorkspace()
        {
#endif
            _deserializer = new DeserializerBuilder()
                .WithNamingConvention(UnderscoredNamingConvention.Instance)
                .IgnoreUnmatchedProperties()
                .Build();

            _serializer = new SerializerBuilder()
                .WithNamingConvention(UnderscoredNamingConvention.Instance)
                .ConfigureDefaultValuesHandling(DefaultValuesHandling.OmitNull)
                .Build();
        }

        private static bool IsSafPath(string path) => path?.StartsWith("content://") == true;

        public async Task<Dictionary<string, Match>> ReadFromFolderAsync(string folderPath, CancellationToken ct = default)
        {
            var (dict, _) = await ReadFromFolderWithImportsAsync(folderPath, ct);
            return dict;
        }

        public async Task<(Dictionary<string, Match> dict, List<Var> globalVars)> ReadFromFolderWithImportsAsync(string folderPath, CancellationToken ct = default)
        {
            var result = new Dictionary<string, Match>();
            var allVars = new List<Var>();
            var visited = new HashSet<string>();

            if (IsSafPath(folderPath))
            {
#if ANDROID
                if (_safManager == null)
                    return (result, allVars);
                var safFiles = _safManager.ListYamlFiles(folderPath);
                var uriLookup = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                foreach (var uri in safFiles)
                {
                    var name = GetLastSegmentUri(uri);
                    if (!string.IsNullOrEmpty(name))
                        uriLookup[name] = uri;
                }
                foreach (var fileUri in safFiles)
                {
                    ct.ThrowIfCancellationRequested();
                    await SafReadWithImportsRecursive(fileUri, folderPath, uriLookup, result, allVars, visited, 0, ct);
                }
                return (result, allVars);
#else
                return (result, allVars);
#endif
            }

            if (!Directory.Exists(folderPath))
                return (result, allVars);

            var files = EnumerateYamlFiles(folderPath);
            foreach (var file in files)
            {
                ct.ThrowIfCancellationRequested();
                await ReadWithImportsRecursive(file, result, allVars, visited, 0, ct);
            }

            return (result, allVars);
        }

        public async Task<MatchGroup> ReadFileAsync(string filePath, CancellationToken ct = default)
        {
            if (IsSafPath(filePath))
            {
#if ANDROID
                if (_safManager == null)
                    return new MatchGroup();
                var text = await _safManager.ReadFileAsync(filePath);
                if (string.IsNullOrEmpty(text))
                    return new MatchGroup();
                return ParseYaml(text, filePath);
#else
                return new MatchGroup();
#endif
            }
            var fsText = await File.ReadAllTextAsync(filePath, ct);
            return ParseYaml(fsText, filePath);
        }

#nullable enable
        public MatchGroup ParseYaml(string yamlText, string? sourceFile = null)
#nullable restore
        {
            var dynamicResult = _deserializer.Deserialize<Dictionary<string, object>>(yamlText);
            if (dynamicResult == null)
                return new MatchGroup { SourceFile = sourceFile };

            var group = new MatchGroup { SourceFile = sourceFile };

            if (dynamicResult.TryGetValue("matches", out var matchesObj) && matchesObj is List<object> matchesList)
            {
                foreach (var matchObj in matchesList)
                {
                    var match = DeserializeMatch(matchObj as Dictionary<string, object>);
                    if (match != null)
                        group.Matches.Add(match);
                }
            }

            if (dynamicResult.TryGetValue("global_vars", out var varsObj) && varsObj is List<object> varsList)
            {
                foreach (var varObj in varsList)
                {
                    var v = DeserializeVar(varObj as Dictionary<string, object>);
                    if (v != null)
                        group.GlobalVars.Add(v);
                }
            }

            if (dynamicResult.TryGetValue("imports", out var importsObj) && importsObj is List<object> importsList)
            {
                group.Imports = importsList.Select(x => x?.ToString()).Where(s => !string.IsNullOrEmpty(s)).ToList();
            }

            var knownKeys = new HashSet<string> { "matches", "global_vars", "imports" };
            foreach (var kv in dynamicResult)
            {
                if (!knownKeys.Contains(kv.Key))
                    group.UnknownFields[kv.Key] = kv.Value;
            }

            return group;
        }

        public async Task WriteFileAsync(string filePath, MatchGroup group, CancellationToken ct = default)
        {
            var dict = BuildSerializableDict(group);
            var yaml = _serializer.Serialize(dict);
            if (IsSafPath(filePath))
            {
#if ANDROID
                if (_safManager != null)
                    await _safManager.WriteFileAsync(filePath, yaml);
#endif
                return;
            }
            await File.WriteAllTextAsync(filePath, yaml, ct);
        }

        public string SerializeMatchGroup(MatchGroup group)
        {
            var dict = BuildSerializableDict(group);
            return _serializer.Serialize(dict);
        }

        public async Task WriteToFolderAsync(string folderPath, Dictionary<string, Match> dict, List<Var> globalVars = null, CancellationToken ct = default)
        {
            if (IsSafPath(folderPath))
            {
#if ANDROID
                if (_safManager == null) return;
                var existingFiles = _safManager.ListYamlFiles(folderPath);
                foreach (var f in existingFiles)
                {
                    try { _safManager.DeleteFileAsync(f); } catch { }
                }

                var grouped = GroupMatchesByTriggerPrefix(dict);
                foreach (var (fileName, matches) in grouped)
                {
                    var docUri = _safManager.CreateDocumentUri(folderPath, fileName);
                    if (docUri == null) continue;
                    var group = new MatchGroup { Matches = matches };
                    await WriteFileAsync(docUri, group, ct);
                }

                if (globalVars != null && globalVars.Count > 0)
                {
                    var gvDocUri = _safManager.CreateDocumentUri(folderPath, "global_vars.yml");
                    if (gvDocUri != null)
                    {
                        var gvGroup = new MatchGroup { GlobalVars = globalVars };
                        await WriteFileAsync(gvDocUri, gvGroup, ct);
                    }
                }
#endif
                return;
            }

            Directory.CreateDirectory(folderPath);

            var fsExistingFiles = EnumerateYamlFiles(folderPath);
            foreach (var f in fsExistingFiles)
            {
                if (!Path.GetFileName(f).StartsWith("."))
                {
                    try { File.Delete(f); } catch { }
                }
            }

            var fsGrouped = GroupMatchesByTriggerPrefix(dict);
            foreach (var (fileName, matches) in fsGrouped)
            {
                var targetFile = Path.Combine(folderPath, fileName);
                var group = new MatchGroup { Matches = matches };
                await WriteFileAsync(targetFile, group, ct);
            }

            if (globalVars != null && globalVars.Count > 0)
            {
                var gvFile = Path.Combine(folderPath, "global_vars.yml");
                var gvGroup = new MatchGroup { GlobalVars = globalVars };
                await WriteFileAsync(gvFile, gvGroup, ct);
            }
        }

        private List<(string fileName, List<Match> matches)> GroupMatchesByTriggerPrefix(Dictionary<string, Match> dict)
        {
            var groups = new Dictionary<string, List<Match>>();
            foreach (var kv in dict)
            {
                var trigger = kv.Key;
                var prefix = GetGroupPrefix(trigger);
                if (!groups.ContainsKey(prefix))
                    groups[prefix] = new List<Match>();
                groups[prefix].Add(kv.Value);
            }
            return groups.Select(g => (g.Key + ".yml", g.Value)).ToList();
        }

        private static string GetGroupPrefix(string trigger)
        {
            if (string.IsNullOrEmpty(trigger)) return "misc";
            var c = trigger[0];
            if (char.IsLetter(c)) return "base";
            if (c == ':') return "emoji";
            if (c == '/' || c == '\\') return "symbols";
            return "misc";
        }

        public List<SyncFileInfo> GetFileList(string folderPath)
        {
            var result = new List<SyncFileInfo>();

            if (IsSafPath(folderPath))
            {
#if ANDROID
                if (_safManager == null) return result;
                var safFiles = _safManager.ListYamlFilesWithMetadata(folderPath);
                foreach (var fi in safFiles)
                {
                    var lastMod = fi.LastModified > 0
                        ? DateTimeOffset.FromUnixTimeMilliseconds(fi.LastModified).UtcDateTime
                        : DateTime.UtcNow;
                    result.Add(new SyncFileInfo
                    {
                        Path = fi.Uri,
                        Size = fi.Size,
                        LastModified = lastMod,
                        Hash = _safManager.ComputeHash(fi.Uri) ?? ""
                    });
                }
                return result;
#else
                return result;
#endif
            }

            if (!Directory.Exists(folderPath))
                return result;

            foreach (var file in EnumerateYamlFiles(folderPath))
            {
                var fi = new System.IO.FileInfo(file);
                result.Add(new SyncFileInfo
                {
                    Path = file,
                    Size = fi.Length,
                    LastModified = fi.LastWriteTimeUtc,
                    Hash = ComputeMd5(file)
                });
            }
            return result;
        }

        public async Task<List<string>> ResolveImportsAsync(string baseDir, List<string> imports, CancellationToken ct = default)
        {
            var resolved = new List<string>();
            if (imports == null) return resolved;

            foreach (var importPath in imports)
            {
                ct.ThrowIfCancellationRequested();
                var fullPath = Path.IsPathRooted(importPath)
                    ? importPath
                    : Path.Combine(baseDir, importPath);

                if (!File.Exists(fullPath))
                {
                    fullPath = Path.GetFullPath(fullPath);
                    if (!File.Exists(fullPath))
                        continue;
                }

                resolved.Add(Path.GetFullPath(fullPath));
            }
            return resolved;
        }

        public async Task<Dictionary<string, Match>> ReadWithImportsAsync(string filePath, CancellationToken ct = default)
        {
            var result = new Dictionary<string, Match>();
            var allVars = new List<Var>();
            var visited = new HashSet<string>();

            await ReadWithImportsRecursive(filePath, result, allVars, visited, 0, ct);
            return result;
        }

        private async Task ReadWithImportsRecursive(string filePath, Dictionary<string, Match> dict, List<Var> vars, HashSet<string> visited, int depth, CancellationToken ct)
        {
            if (depth > MaxImportDepth) return;

            var normalized = Path.GetFullPath(filePath);
            if (visited.Contains(normalized)) return;
            visited.Add(normalized);

            if (!File.Exists(normalized)) return;

            var group = await ReadFileAsync(normalized, ct);
            MergeGroupIntoDict(dict, vars, group);

            if (group.Imports != null && group.Imports.Count > 0)
            {
                var baseDir = Path.GetDirectoryName(normalized);
                var resolved = await ResolveImportsAsync(baseDir, group.Imports, ct);
                foreach (var importFile in resolved)
                {
                    await ReadWithImportsRecursive(importFile, dict, vars, visited, depth + 1, ct);
                }
            }
        }

#if ANDROID
        private async Task SafReadWithImportsRecursive(
            string fileUri, string treeUri, Dictionary<string, string> uriLookup,
            Dictionary<string, Match> dict, List<Var> vars,
            HashSet<string> visited, int depth, CancellationToken ct)
        {
            if (depth > MaxImportDepth) return;
            if (visited.Contains(fileUri)) return;
            visited.Add(fileUri);

            var text = await _safManager.ReadFileAsync(fileUri);
            if (string.IsNullOrEmpty(text)) return;

            var group = ParseYaml(text, fileUri);
            MergeGroupIntoDict(dict, vars, group);

            if (group.Imports != null && group.Imports.Count > 0)
            {
                foreach (var importPath in group.Imports)
                {
                    ct.ThrowIfCancellationRequested();
                    if (string.IsNullOrEmpty(importPath)) continue;

                    var importName = GetLastSegmentUri(importPath);
                    if (string.IsNullOrEmpty(importName)) continue;

                    if (uriLookup.TryGetValue(importName, out var resolvedUri))
                    {
                        await SafReadWithImportsRecursive(
                            resolvedUri, treeUri, uriLookup, dict, vars, visited, depth + 1, ct);
                    }
                }
            }
        }
#endif

        private static string GetLastSegmentUri(string path)
        {
            if (string.IsNullOrEmpty(path)) return "";
            var idx = path.LastIndexOf('/');
            return idx >= 0 ? path.Substring(idx + 1) : path;
        }

        public ConfigSyncFields ExtractConfigFields(string configDir)
        {
            var result = new ConfigSyncFields();
            if (!Directory.Exists(configDir))
                return result;

            var yamlFiles = EnumerateYamlFiles(configDir);
            foreach (var file in yamlFiles)
            {
                try
                {
                    var text = File.ReadAllText(file);
                    var dynamicResult = _deserializer.Deserialize<Dictionary<string, object>>(text);
                    if (dynamicResult == null) continue;

                    if (dynamicResult.TryGetValue("use_standard_includes", out var usi) && usi is bool b)
                        result.UseStandardIncludes = b;

                    if (dynamicResult.TryGetValue("includes", out var inc) && inc is List<object> incList)
                        result.Includes.AddRange(incList.Select(x => x?.ToString()));

                    if (dynamicResult.TryGetValue("extra_includes", out var einc) && einc is List<object> eincList)
                        result.ExtraIncludes.AddRange(eincList.Select(x => x?.ToString()));

                    if (dynamicResult.TryGetValue("excludes", out var exc) && exc is List<object> excList)
                        result.Excludes.AddRange(excList.Select(x => x?.ToString()));

                    if (dynamicResult.TryGetValue("extra_excludes", out var eexc) && eexc is List<object> eexcList)
                        result.ExtraExcludes.AddRange(eexcList.Select(x => x?.ToString()));

                    if (dynamicResult.TryGetValue("match_paths", out var mp) && mp is List<object> mpList)
                        result.MatchPaths.AddRange(mpList.Select(x => x?.ToString()));
                }
                catch { }
            }
            return result;
        }

        public List<string> CalculateMatchPaths(string configDir, string matchDir)
        {
            var configFields = ExtractConfigFields(configDir);
            var includePaths = new HashSet<string>();
            var excludePaths = new HashSet<string>();

            if (configFields.UseStandardIncludes)
            {
                var standardFiles = EnumerateYamlFiles(matchDir);
                foreach (var f in standardFiles)
                    includePaths.Add(Path.GetFullPath(f));
            }

            foreach (var pattern in configFields.Includes.Concat(configFields.ExtraIncludes))
            {
                var matched = GlobMatch(matchDir, pattern);
                foreach (var m in matched)
                    includePaths.Add(m);
            }

            foreach (var pattern in configFields.Excludes.Concat(configFields.ExtraExcludes))
            {
                var matched = GlobMatch(matchDir, pattern);
                foreach (var m in matched)
                    excludePaths.Add(m);
            }

            foreach (var mp in configFields.MatchPaths)
            {
                var fullPath = Path.IsPathRooted(mp) ? mp : Path.Combine(configDir, mp);
                if (File.Exists(fullPath))
                    includePaths.Add(Path.GetFullPath(fullPath));
            }

            includePaths.ExceptWith(excludePaths);
            return includePaths.ToList();
        }

        private List<string> GlobMatch(string baseDir, string pattern)
        {
            var result = new List<string>();
            if (string.IsNullOrEmpty(pattern))
                return result;

            var isAbsolute = Path.IsPathRooted(pattern);
            var searchRoot = isAbsolute
                ? Path.GetDirectoryName(pattern)
                : Path.Combine(baseDir, Path.GetDirectoryName(pattern) ?? "");

            var filePattern = Path.GetFileName(pattern);

            if (!Directory.Exists(searchRoot))
                return result;

            if (pattern.Contains("**"))
            {
                var files = EnumerateYamlFiles(searchRoot);
                foreach (var f in files)
                {
                    var fileName = Path.GetFileName(f);
                    if (!fileName.StartsWith("_") && MatchesSimpleGlob(filePattern, fileName))
                        result.Add(Path.GetFullPath(f));
                }
            }
            else
            {
                try
                {
                    foreach (var f in Directory.EnumerateFiles(searchRoot, filePattern))
                    {
                        var fileName = Path.GetFileName(f);
                        if (!fileName.StartsWith("_"))
                            result.Add(Path.GetFullPath(f));
                    }
                }
                catch { }
            }

            return result;
        }

        private bool MatchesSimpleGlob(string pattern, string fileName)
        {
            if (pattern == "*.yml" || pattern == "*.yaml")
                return fileName.EndsWith(pattern.Substring(1));

            if (pattern.Contains("*") || pattern.Contains("?") || pattern.Contains("["))
            {
                var sb = new System.Text.StringBuilder();
                sb.Append('^');
                int i = 0;
                while (i < pattern.Length)
                {
                    char c = pattern[i];
                    switch (c)
                    {
                        case '*': sb.Append(".*"); break;
                        case '?': sb.Append('.'); break;
                        case '[':
                            var closeIdx = pattern.IndexOf(']', i + 1);
                            if (closeIdx > i + 1)
                            {
                                var charClass = pattern.Substring(i + 1, closeIdx - i - 1);
                                if (charClass.StartsWith("!"))
                                    sb.Append("[").Append(charClass.Substring(0, 1)).Append("^").Append(RegexEscapeClass(charClass.Substring(1))).Append("]");
                                else
                                    sb.Append("[").Append(RegexEscapeClass(charClass)).Append("]");
                                i = closeIdx;
                                break;
                            }
                            sb.Append('['); break;
                        default:
                            sb.Append(System.Text.RegularExpressions.Regex.Escape(c.ToString())); break;
                    }
                    i++;
                }
                sb.Append('$');
                return System.Text.RegularExpressions.Regex.IsMatch(fileName, sb.ToString());
            }

            return fileName == pattern;
        }

        private static string RegexEscapeClass(string charClass)
        {
            return charClass.Replace("\\", "\\\\").Replace("]", "\\]").Replace("^", "\\^");
        }

        private List<string> EnumerateYamlFiles(string dir)
        {
            var result = new List<string>();
            if (!Directory.Exists(dir))
                return result;

            foreach (var ext in YamlExtensions)
            {
                try
                {
                    foreach (var file in Directory.EnumerateFiles(dir, "*" + ext, SearchOption.AllDirectories))
                    {
                        var fileName = Path.GetFileName(file);
                        if (!fileName.StartsWith("_"))
                            result.Add(file);
                    }
                }
                catch { }
            }
            return result;
        }

        public void MergeGroupIntoDict(Dictionary<string, Match> dict, List<Var> vars, MatchGroup group)
        {
            if (group.GlobalVars != null)
                vars.AddRange(group.GlobalVars);

            if (group.Matches == null) return;

            foreach (var item in group.Matches)
            {
                if (item.Triggers is not null && item.Triggers.Count > 0)
                {
                    foreach (var t in item.Triggers)
                    {
                        var clone = new Match(item) { Trigger = t };
                        dict[t] = clone;
                    }
                }
                else if (!string.IsNullOrEmpty(item.Trigger))
                {
                    dict[item.Trigger] = item;
                }
                else if (!string.IsNullOrEmpty(item.Regex))
                {
                    dict[$"__regex_{item.Regex}"] = item;
                }
            }
        }

        private Dictionary<string, object> BuildSerializableDict(MatchGroup group)
        {
            var dict = new Dictionary<string, object>();

            if (group.Matches != null && group.Matches.Count > 0)
                dict["matches"] = group.Matches;

            if (group.GlobalVars != null && group.GlobalVars.Count > 0)
                dict["global_vars"] = group.GlobalVars;

            if (group.Imports != null && group.Imports.Count > 0)
                dict["imports"] = group.Imports;

            if (group.UnknownFields != null)
            {
                foreach (var kv in group.UnknownFields)
                    dict[kv.Key] = kv.Value;
            }

            return dict;
        }

        private Match DeserializeMatch(Dictionary<string, object> obj)
        {
            if (obj == null) return null;
            try
            {
                var json = System.Text.Json.JsonSerializer.Serialize(obj);
                var match = System.Text.Json.JsonSerializer.Deserialize<Match>(json, new System.Text.Json.JsonSerializerOptions
                {
                    PropertyNamingPolicy = System.Text.Json.JsonNamingPolicy.SnakeCaseLower
                });
                return match;
            }
            catch
            {
                return null;
            }
        }

        private Var DeserializeVar(Dictionary<string, object> obj)
        {
            if (obj == null) return null;
            try
            {
                var json = System.Text.Json.JsonSerializer.Serialize(obj);
                var v = System.Text.Json.JsonSerializer.Deserialize<Var>(json, new System.Text.Json.JsonSerializerOptions
                {
                    PropertyNamingPolicy = System.Text.Json.JsonNamingPolicy.SnakeCaseLower
                });
                return v;
            }
            catch
            {
                return null;
            }
        }

        public static string ComputeMd5(string filePath)
        {
            using var md5 = MD5.Create();
            using var stream = File.OpenRead(filePath);
            var hash = md5.ComputeHash(stream);
            return BitConverter.ToString(hash).Replace("-", "").ToLowerInvariant();
        }

        public static string ComputeMd5Text(string text)
        {
            using var md5 = MD5.Create();
            var bytes = Encoding.UTF8.GetBytes(text);
            var hash = md5.ComputeHash(bytes);
            return BitConverter.ToString(hash).Replace("-", "").ToLowerInvariant();
        }
    }
}
